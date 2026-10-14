package au.com.ausroads.routing.engine.valhalla

import au.com.ausroads.core.model.GeoPoint
import au.com.ausroads.routing.engine.RouteRequest
import com.google.common.truth.Truth.assertThat
import com.valhalla.api.models.RouteRequest as ValhallaRouteRequest
import com.valhalla.valhalla.Valhalla
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/** Real engine dispatcher with a controlled construction/native seam; not JNI/graph proof. */
class ValhallaRoutingEngineTest {
    @Test
    fun `uninitialized engine is unavailable and request never constructs a router`() {
        val calls = AtomicInteger()
        val engine = ValhallaRoutingEngine {
            calls.incrementAndGet()
            error("unexpected construction")
        }
        assertThat(engine.isReady()).isFalse()
        assertUnavailable(engine)
        assertThat(calls.get()).isEqualTo(0)
    }

    @Test
    fun `readiness is published only after construction and same path is revalidated`() {
        val native = mockk<Valhalla>()
        val calls = AtomicInteger()
        lateinit var engine: ValhallaRoutingEngine
        engine = ValhallaRoutingEngine {
            assertThat(engine.isReady()).isFalse()
            calls.incrementAndGet()
            native
        }
        engine.initialize("same.tar")
        assertThat(engine.isReady()).isTrue()
        engine.initialize("same.tar")
        assertThat(engine.isReady()).isTrue()
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `failed replacement clears old router and repaired same path can retry`() {
        val old = mockk<Valhalla>()
        val replacement = mockk<Valhalla>()
        val observed = NativeCallObserved()
        every { replacement.route(any<ValhallaRouteRequest>()) } throws observed
        val paths = mutableListOf<String>()
        var failReplacement = true
        val engine = ValhallaRoutingEngine { path ->
            paths += path
            if (path == "old.tar") old
            else {
                check(!failReplacement) { "configuration write control" }
                replacement
            }
        }
        engine.initialize("old.tar")
        assertThat(engine.isReady()).isTrue()
        assertThrows(IllegalStateException::class.java) { engine.initialize("replacement.tar") }
        assertThat(engine.isReady()).isFalse()
        assertUnavailable(engine)
        verify(exactly = 0) { old.route(any<ValhallaRouteRequest>()) }
        failReplacement = false
        engine.initialize("replacement.tar")
        assertThat(engine.isReady()).isTrue()
        assertThat(assertThrows(NativeCallObserved::class.java) {
            runBlocking { engine.computeRoute(request()) }
        }).isSameInstanceAs(observed)
        assertThat(paths).containsExactly("old.tar", "replacement.tar", "replacement.tar").inOrder()
    }

    @Test
    fun `explicit invalidation prevents all subsequent native requests`() {
        val native = mockk<Valhalla>()
        val engine = ValhallaRoutingEngine { native }
        engine.initialize("tiles.tar")
        engine.invalidate()
        engine.invalidate()
        assertThat(engine.isReady()).isFalse()
        assertUnavailable(engine)
        verify(exactly = 0) { native.route(any<ValhallaRouteRequest>()) }
        engine.initialize("tiles.tar")
        assertThat(engine.isReady()).isTrue()
    }

    @Test
    fun `native request fences configuration replacement`() = assertNativeFence(replace = true)

    @Test
    fun `native request fences invalidation`() = assertNativeFence(replace = false)

    @Test
    fun `request waits for configuration publication and dispatches native off caller thread`() {
        val native = mockk<Valhalla>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requestStarted = CountDownLatch(1)
        val nativeEntered = CountDownLatch(1)
        val observed = NativeCallObserved()
        lateinit var callerThread: Thread
        every { native.route(any<ValhallaRouteRequest>()) } answers {
            assertThat(Thread.currentThread()).isNotSameInstanceAs(callerThread)
            nativeEntered.countDown()
            throw observed
        }
        val engine = ValhallaRoutingEngine {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            native
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val initialize = workers.submit { engine.initialize("tiles.tar") }
            check(entered.await(5, TimeUnit.SECONDS))
            assertThat(engine.isReady()).isFalse()
            val route = workers.submit<Throwable?> {
                callerThread = Thread.currentThread()
                requestStarted.countDown()
                runCatching { runBlocking { engine.computeRoute(request()) } }.exceptionOrNull()
            }
            check(requestStarted.await(5, TimeUnit.SECONDS))
            assertThat(nativeEntered.await(200, TimeUnit.MILLISECONDS)).isFalse()
            assertThat(route.isDone).isFalse()
            release.countDown()
            initialize.get(5, TimeUnit.SECONDS)
            assertThat(route.get(5, TimeUnit.SECONDS)).isSameInstanceAs(observed)
            assertThat(nativeEntered.count).isEqualTo(0L)
            assertThat(engine.isReady()).isTrue()
        } finally {
            release.countDown()
            workers.shutdownNow()
            check(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertNativeFence(replace: Boolean) {
        val native = mockk<Valhalla>()
        val calls = AtomicInteger()
        val engine = ValhallaRoutingEngine { calls.incrementAndGet(); native }
        engine.initialize("old.tar")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val actionStarted = CountDownLatch(1)
        val actionFinished = CountDownLatch(1)
        val observed = NativeCallObserved()
        every { native.route(any<ValhallaRouteRequest>()) } answers {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            throw observed
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val route = workers.submit<Throwable?> {
                runCatching { runBlocking { engine.computeRoute(request()) } }.exceptionOrNull()
            }
            check(entered.await(5, TimeUnit.SECONDS))
            val change = workers.submit {
                actionStarted.countDown()
                if (replace) engine.initialize("new.tar") else engine.invalidate()
                actionFinished.countDown()
            }
            check(actionStarted.await(5, TimeUnit.SECONDS))
            assertThat(actionFinished.await(200, TimeUnit.MILLISECONDS)).isFalse()
            assertThat(calls.get()).isEqualTo(1)
            assertThat(engine.isReady()).isTrue()
            release.countDown()
            assertThat(route.get(5, TimeUnit.SECONDS)).isSameInstanceAs(observed)
            change.get(5, TimeUnit.SECONDS)
            assertThat(calls.get()).isEqualTo(if (replace) 2 else 1)
            assertThat(engine.isReady()).isEqualTo(replace)
            if (!replace) assertUnavailable(engine)
        } finally {
            release.countDown()
            workers.shutdownNow()
            check(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertUnavailable(engine: ValhallaRoutingEngine) {
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { engine.computeRoute(request()) }
        }
        assertThat(failure.message).contains("not initialized")
    }

    private fun request() = RouteRequest(
        origin = GeoPoint(latitude = -34.9285, longitude = 138.6007),
        destination = GeoPoint(latitude = -34.9066, longitude = 138.5950),
    )

    private class NativeCallObserved : RuntimeException()
}

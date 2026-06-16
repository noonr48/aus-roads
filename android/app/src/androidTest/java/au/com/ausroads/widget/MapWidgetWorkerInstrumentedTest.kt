package au.com.ausroads.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runtime proof that WorkManager + the Hilt worker factory are wired correctly.
 *
 * Enqueues a real [MapWidgetWorker] against the production WorkManager — which is
 * initialized on demand from AusRoadsApp's `Configuration.Provider` +
 * `HiltWorkerFactory` — and asserts it reaches SUCCEEDED. The original defect was
 * that the default WorkManager initializer shadowed the Hilt factory, so the
 * widget worker (which needs constructor injection) could not be instantiated.
 * If the factory cannot construct the worker, this work never succeeds.
 *
 * Deliberately uses the real AusRoadsApp via the default [AndroidJUnit4] runner —
 * NOT a Hilt test application and NOT `WorkManagerTestInitHelper` — because both
 * would replace the exact production wiring this test exists to verify.
 */
@RunWith(AndroidJUnit4::class)
class MapWidgetWorkerInstrumentedTest {

    @Test
    fun mapWidgetWorker_runsToSuccess_viaHiltWorkerFactory() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workManager = WorkManager.getInstance(context)

        val request = OneTimeWorkRequestBuilder<MapWidgetWorker>().build()
        workManager.enqueue(request).result.get()

        val deadlineMs = System.currentTimeMillis() + TIMEOUT_MS
        var info = workManager.getWorkInfoById(request.id).get()
        while ((info == null || !info.state.isFinished) && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(POLL_MS)
            info = workManager.getWorkInfoById(request.id).get()
        }

        assertThat(info).isNotNull()
        assertThat(info!!.state).isEqualTo(WorkInfo.State.SUCCEEDED)
    }

    private companion object {
        private const val TIMEOUT_MS = 30_000L
        private const val POLL_MS = 200L
    }
}

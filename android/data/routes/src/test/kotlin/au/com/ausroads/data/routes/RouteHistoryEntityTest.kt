package au.com.ausroads.data.routes

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Clock
import org.junit.Test

class RouteHistoryEntityTest {

    private fun createEntity(
        id: Long = 0,
        originLat: Double = -33.8688,
        originLon: Double = 151.2093,
        destLat: Double = -37.8136,
        destLon: Double = 144.9631,
        originName: String = "",
        destName: String = "",
        distanceMeters: Int = 714_000,
        durationSeconds: Int = 36_000,
    ) = RouteHistoryEntity(
        id = id,
        originLat = originLat,
        originLon = originLon,
        destLat = destLat,
        destLon = destLon,
        originName = originName,
        destName = destName,
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
    )

    @Test
    fun `id defaults to zero`() {
        val entity = createEntity()

        assertThat(entity.id).isEqualTo(0L)
    }

    @Test
    fun `originName defaults to empty string`() {
        val entity = createEntity()

        assertThat(entity.originName).isEmpty()
    }

    @Test
    fun `destName defaults to empty string`() {
        val entity = createEntity()

        assertThat(entity.destName).isEmpty()
    }

    @Test
    fun `createdAt defaults to approximately now`() {
        val before = Clock.System.now()
        val entity = createEntity()
        val after = Clock.System.now()

        assertThat(entity.createdAt).isAtLeast(before)
        assertThat(entity.createdAt).isAtMost(after)
    }

    @Test
    fun `all fields are accessible and hold correct values`() {
        val entity = RouteHistoryEntity(
            id = 42,
            originLat = -33.8688,
            originLon = 151.2093,
            destLat = -37.8136,
            destLon = 144.9631,
            originName = "Sydney",
            destName = "Melbourne",
            distanceMeters = 714_000,
            durationSeconds = 36_000,
        )

        assertThat(entity.id).isEqualTo(42L)
        assertThat(entity.originLat).isWithin(0.0001).of(-33.8688)
        assertThat(entity.originLon).isWithin(0.0001).of(151.2093)
        assertThat(entity.destLat).isWithin(0.0001).of(-37.8136)
        assertThat(entity.destLon).isWithin(0.0001).of(144.9631)
        assertThat(entity.originName).isEqualTo("Sydney")
        assertThat(entity.destName).isEqualTo("Melbourne")
        assertThat(entity.distanceMeters).isEqualTo(714_000)
        assertThat(entity.durationSeconds).isEqualTo(36_000)
    }

    @Test
    fun `data class equality holds for identical entities`() {
        val now = Clock.System.now()
        val a = RouteHistoryEntity(
            id = 1,
            originLat = -33.8688,
            originLon = 151.2093,
            destLat = -37.8136,
            destLon = 144.9631,
            originName = "Sydney",
            destName = "Melbourne",
            distanceMeters = 714_000,
            durationSeconds = 36_000,
            createdAt = now,
        )
        val b = RouteHistoryEntity(
            id = 1,
            originLat = -33.8688,
            originLon = 151.2093,
            destLat = -37.8136,
            destLon = 144.9631,
            originName = "Sydney",
            destName = "Melbourne",
            distanceMeters = 714_000,
            durationSeconds = 36_000,
            createdAt = now,
        )

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `data class equality fails for different entities`() {
        val a = createEntity(originName = "Sydney")
        val b = createEntity(originName = "Canberra")

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `copy produces equal entity`() {
        val original = createEntity()
        val copy = original.copy(destName = "Melbourne CBD")

        assertThat(copy.id).isEqualTo(original.id)
        assertThat(copy.originLat).isEqualTo(original.originLat)
        assertThat(copy.destName).isEqualTo("Melbourne CBD")
    }
}

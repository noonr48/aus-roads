package au.com.ausroads.data.reports

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test

/*
 * Entity-defaults test in the RouteHistoryEntityTest style (:data:routes).
 */
class CommunityReportEntityTest {

    private fun createEntity(
        id: Long = 0,
        category: ReportCategory = ReportCategory.HAZARD,
        lat: Double = -34.9285,
        lon: Double = 138.6007,
        createdAt: Instant = Clock.System.now(),
    ) = CommunityReport(
        id = id,
        category = category,
        lat = lat,
        lon = lon,
        createdAt = createdAt,
    )

    @Test
    fun `id defaults to zero`() {
        assertThat(createEntity().id).isEqualTo(0L)
    }

    @Test
    fun `name defaults to null`() {
        assertThat(createEntity().name).isNull()
    }

    @Test
    fun `note defaults to empty string`() {
        assertThat(createEntity().note).isEmpty()
    }

    @Test
    fun `photoPath defaults to null (reserved for future)`() {
        assertThat(createEntity().photoPath).isNull()
    }

    @Test
    fun `expiresAt defaults to null (never expires unless set)`() {
        assertThat(createEntity().expiresAt).isNull()
    }

    @Test
    fun `confirmCount and rejectCount default to zero`() {
        val entity = createEntity()

        assertThat(entity.confirmCount).isEqualTo(0)
        assertThat(entity.rejectCount).isEqualTo(0)
    }

    @Test
    fun `syncState defaults to LOCAL`() {
        assertThat(createEntity().syncState).isEqualTo("LOCAL")
    }

    @Test
    fun `all fields hold explicitly assigned values`() {
        val created = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val expires = Instant.fromEpochMilliseconds(1_700_018_000_000L)
        val entity = CommunityReport(
            id = 7,
            category = ReportCategory.MOBILE_SPEED_CAMERA,
            name = "Main North Rd",
            lat = -34.8181,
            lon = 138.6435,
            note = "near the roundabout",
            photoPath = "/tmp/photo.jpg",
            createdAt = created,
            expiresAt = expires,
            confirmCount = 3,
            rejectCount = 1,
            syncState = "LOCAL",
        )

        assertThat(entity.id).isEqualTo(7L)
        assertThat(entity.category).isEqualTo(ReportCategory.MOBILE_SPEED_CAMERA)
        assertThat(entity.name).isEqualTo("Main North Rd")
        assertThat(entity.lat).isWithin(0.0001).of(-34.8181)
        assertThat(entity.lon).isWithin(0.0001).of(138.6435)
        assertThat(entity.note).isEqualTo("near the roundabout")
        assertThat(entity.photoPath).isEqualTo("/tmp/photo.jpg")
        assertThat(entity.createdAt).isEqualTo(created)
        assertThat(entity.expiresAt).isEqualTo(expires)
        assertThat(entity.confirmCount).isEqualTo(3)
        assertThat(entity.rejectCount).isEqualTo(1)
        assertThat(entity.syncState).isEqualTo("LOCAL")
    }

    @Test
    fun `data class equality holds for identical entities`() {
        val now = Clock.System.now()
        val a = createEntity(id = 1, createdAt = now)
        val b = createEntity(id = 1, createdAt = now)

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `data class equality fails for different categories`() {
        assertThat(createEntity(category = ReportCategory.SHOP))
            .isNotEqualTo(createEntity(category = ReportCategory.ACCIDENT))
    }

    @Test
    fun `copy produces equal entity`() {
        val original = createEntity()
        val copy = original.copy(note = "updated")

        assertThat(copy.id).isEqualTo(original.id)
        assertThat(copy.lat).isEqualTo(original.lat)
        assertThat(copy.note).isEqualTo("updated")
    }
}

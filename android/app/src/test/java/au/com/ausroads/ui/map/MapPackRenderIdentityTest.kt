package au.com.ausroads.ui.map

import au.com.ausroads.offline.download.state.InstalledPack
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

class MapPackRenderIdentityTest {

    @Test
    fun `reconciliation to no installed pack switches stale identity to bundled`() {
        val staleDescriptorIdentity = "broken-version:2026-06-01T00:00:00Z"

        assertThat(
            mapPackRenderIdentity(
                initialPackIdentity = staleDescriptorIdentity,
                activePackIdentity = null,
                packStateInitialized = false,
            ),
        ).isEqualTo(staleDescriptorIdentity)

        assertThat(
            mapPackRenderIdentity(
                initialPackIdentity = staleDescriptorIdentity,
                activePackIdentity = null,
                packStateInitialized = true,
            ),
        ).isEqualTo("bundled")
    }

    @Test
    fun `settled installed pack identity replaces the launch snapshot`() {
        assertThat(
            mapPackRenderIdentity(
                initialPackIdentity = "bundled",
                activePackIdentity = "2026-07-16:2026-07-16T12:30:00Z",
                packStateInitialized = true,
            ),
        ).isEqualTo("2026-07-16:2026-07-16T12:30:00Z")
    }

    @Test
    fun `failed replacement keeps the last activated descriptor instead of disk candidate`() {
        val diskCandidate = installedPack("candidate", "2026-07-16T13:00:00Z")
        val lastActivated = installedPack("stable", "2026-07-16T12:00:00Z")

        assertThat(
            runtimeInstalledPack(
                initialPack = diskCandidate,
                activePack = lastActivated,
                packStateInitialized = true,
            ),
        ).isSameInstanceAs(lastActivated)
    }

    @Test
    fun `launch snapshot is used only until runtime reconciliation settles`() {
        val launchSnapshot = installedPack("launch", "2026-07-16T11:00:00Z")

        assertThat(
            runtimeInstalledPack(
                initialPack = launchSnapshot,
                activePack = null,
                packStateInitialized = false,
            ),
        ).isSameInstanceAs(launchSnapshot)
        assertThat(
            runtimeInstalledPack(
                initialPack = launchSnapshot,
                activePack = null,
                packStateInitialized = true,
            ),
        ).isNull()
    }

    private fun installedPack(version: String, installedAt: String) = InstalledPack(
        version = version,
        regionCode = "AU-SA",
        installedAt = Instant.parse(installedAt),
        totalSizeBytes = 1L,
        tilesPath = "tiles.mbtiles",
        searchPath = "search.db",
        routingPath = null,
        manifestSha256 = "test",
    )
}

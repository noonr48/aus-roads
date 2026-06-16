/*
 * Map pack manifest. This is the on-the-wire schema for the in-app downloader to
 * discover the latest SA pack. Locked at v1; v2 changes must be backward-compatible.
 */
package au.com.ausroads.offline.pack

import au.com.ausroads.core.model.Bbox
import au.com.ausroads.core.model.Region
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

@Serializable
data class PackManifest(
    /** Schema version. Bump on breaking changes. */
    val schemaVersion: Int = 1,
    /** Pack version, e.g. "2026-06-01". Used as the install dir name. */
    val packVersion: String,
    /** Region this pack covers. */
    val region: Region,
    /** Bounding box of the pack. */
    val bbox: Bbox,
    /** When the pack was built. */
    val generatedAt: Instant,
    /** Source PBF / extract. */
    val osmSource: OsmSource,
    /** License. Always "ODbL-1.0" for OSM-derived packs. */
    val license: String = "ODbL-1.0",
    /** Minimum app version that can consume this pack. */
    val minAppVersion: String,
    /** Minimum Android SDK. The downloader refuses packs requiring higher. */
    val minAndroidSdk: Int,
    /** Pack components and their hashes. */
    val components: PackComponents,
    /** Total size in bytes (sum of components). */
    val totalSizeBytes: Long,
    /** Optional signature. */
    val signatures: Map<String, String> = emptyMap(),
)

@Serializable
data class OsmSource(
    val provider: String,
    val url: String,
    val osmExtractDate: Instant,
)

@Serializable
data class PackComponents(
    val tiles: TileComponent,
    val routing: RoutingComponent,
    val search: SearchComponent,
)

@Serializable
data class TileComponent(
    val format: String,
    val schema: String,
    val minZoom: Int,
    val maxZoom: Int,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class RoutingComponent(
    val format: String,
    val profile: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class SearchComponent(
    val format: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

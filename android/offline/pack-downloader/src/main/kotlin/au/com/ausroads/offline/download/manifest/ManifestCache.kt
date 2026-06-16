package au.com.ausroads.offline.download.manifest

import au.com.ausroads.offline.download.state.ManifestCacheEntry
import au.com.ausroads.offline.download.state.PackStateStore

class ManifestCache(private val packStateStore: PackStateStore) {
    suspend fun read(): ManifestCacheEntry? = packStateStore.readManifestCache()
    suspend fun write(entry: ManifestCacheEntry) = packStateStore.writeManifestCache(entry)
}

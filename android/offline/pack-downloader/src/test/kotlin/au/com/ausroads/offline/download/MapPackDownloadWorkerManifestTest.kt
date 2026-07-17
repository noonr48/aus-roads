package au.com.ausroads.offline.download

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertThrows
import org.junit.Test

class MapPackDownloadWorkerManifestTest {
    @Test
    fun `legacy request without manifest remains supported`() {
        assertThat(decodeWorkerManifest(null)).isNull()
    }

    @Test
    fun `present malformed manifest fails closed`() {
        assertThrows(SerializationException::class.java) {
            decodeWorkerManifest("{not-a-pack-manifest}")
        }
    }
}

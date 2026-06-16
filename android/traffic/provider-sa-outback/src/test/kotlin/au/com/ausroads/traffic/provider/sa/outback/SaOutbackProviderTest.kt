package au.com.ausroads.traffic.provider.sa.outback

import org.junit.Test
import com.google.common.truth.Truth.assertThat

class SaOutbackProviderTest {

    @Test
    fun `regionCode is AU-SA-OUTBACK`() {
        val provider = SaOutbackProvider()
        assertThat(provider.regionCode).isEqualTo("AU-SA-OUTBACK")
    }

    @Test
    fun `displayName is DIT Outback Roads`() {
        val provider = SaOutbackProvider()
        assertThat(provider.displayName).isEqualTo("DIT Outback Roads")
    }

    @Test
    fun `supportedBbox covers SA outback`() {
        val provider = SaOutbackProvider()
        assertThat(provider.supportedBbox.west).isAtMost(130.0)
        assertThat(provider.supportedBbox.east).isAtLeast(140.0)
        assertThat(provider.supportedBbox.south).isAtMost(-37.0)
        assertThat(provider.supportedBbox.north).isAtLeast(-26.0)
    }
}

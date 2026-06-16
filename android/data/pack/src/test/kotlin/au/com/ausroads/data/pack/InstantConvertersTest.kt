package au.com.ausroads.data.pack

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

class InstantConvertersTest {

    private val converters = InstantConverters()

    @Test
    fun `round-trips epoch zero`() {
        val instant = Instant.fromEpochMilliseconds(0L)

        val millis = converters.fromInstant(instant)
        val restored = converters.toInstant(millis)

        assertThat(restored).isEqualTo(instant)
        assertThat(millis).isEqualTo(0L)
    }

    @Test
    fun `round-trips a positive timestamp`() {
        val instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

        val millis = converters.fromInstant(instant)
        val restored = converters.toInstant(millis)

        assertThat(restored).isEqualTo(instant)
    }

    @Test
    fun `round-trips a negative timestamp (pre-epoch)`() {
        val instant = Instant.fromEpochMilliseconds(-86_400_000L)

        val millis = converters.fromInstant(instant)
        val restored = converters.toInstant(millis)

        assertThat(restored).isEqualTo(instant)
        assertThat(millis).isEqualTo(-86_400_000L)
    }

    @Test
    fun `fromInstant returns epoch milliseconds`() {
        val instant = Instant.fromEpochMilliseconds(42L)

        assertThat(converters.fromInstant(instant)).isEqualTo(42L)
    }

    @Test
    fun `toInstant returns correct Instant`() {
        val instant = converters.toInstant(12345L)

        assertThat(instant).isEqualTo(Instant.fromEpochMilliseconds(12345L))
    }

    @Test
    fun `round-trips a recent timestamp`() {
        val now = Instant.fromEpochMilliseconds(1_735_689_600_000L)

        val millis = converters.fromInstant(now)
        val restored = converters.toInstant(millis)

        assertThat(restored).isEqualTo(now)
    }
}

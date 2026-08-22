package au.com.ausroads.data.reports

import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.Test

/*
 * Instant TypeConverter test, mirroring :data:pins' InstantConvertersTest.
 * The report variant uses nullable signatures so expiresAt can be null.
 */
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
    fun `null round-trips to null`() {
        assertThat(converters.fromInstant(null)).isNull()
        assertThat(converters.toInstant(null)).isNull()
    }
}

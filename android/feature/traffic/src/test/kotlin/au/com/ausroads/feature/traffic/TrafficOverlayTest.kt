package au.com.ausroads.feature.traffic

import au.com.ausroads.traffic.provider.Severity
import androidx.compose.ui.graphics.Color
import org.junit.Test
import com.google.common.truth.Truth.assertThat

class TrafficOverlayTest {

    @Test
    fun `NSW credential failure presents settings recovery with a friendly region name`() {
        val presentation = TrafficFailure.InvalidCredentials("AU-NSW").presentation()

        assertThat(presentation.messageRes).isEqualTo(R.string.traffic_invalid_credentials)
        assertThat(presentation.regionLabel).isEqualTo("NSW")
        assertThat(presentation.actionRes).isEqualTo(R.string.traffic_open_settings)
        assertThat(presentation.recovery).isEqualTo(TrafficRecovery.OPEN_SETTINGS)
    }

    @Test
    fun `generic provider failure keeps retry recovery`() {
        val presentation = TrafficFailure.AllProvidersUnavailable.presentation()

        assertThat(presentation.messageRes).isEqualTo(R.string.traffic_offline)
        assertThat(presentation.regionLabel).isNull()
        assertThat(presentation.actionRes).isEqualTo(R.string.traffic_retry)
        assertThat(presentation.recovery).isEqualTo(TrafficRecovery.RETRY)
    }

    @Test
    fun `severityColor returns green for LOW`() {
        val color = severityColor(Severity.LOW)
        assertThat(color).isEqualTo(Color(0xFF4CAF50))
    }

    @Test
    fun `severityColor returns orange for MEDIUM`() {
        val color = severityColor(Severity.MEDIUM)
        assertThat(color).isEqualTo(Color(0xFFFF9800))
    }

    @Test
    fun `severityColor returns red for HIGH`() {
        val color = severityColor(Severity.HIGH)
        assertThat(color).isEqualTo(Color(0xFFF44336))
    }

    @Test
    fun `severityColor returns purple for CRITICAL`() {
        val color = severityColor(Severity.CRITICAL)
        assertThat(color).isEqualTo(Color(0xFF9C27B0))
    }

    @Test
    fun `severityColor returns distinct colors for all severities`() {
        val colors = Severity.entries.map { severityColor(it) }
        assertThat(colors.toSet()).hasSize(Severity.entries.size)
    }
}

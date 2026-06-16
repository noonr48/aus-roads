package au.com.ausroads.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import au.com.ausroads.MainActivity
import au.com.ausroads.R

class MapWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            val state = prefs.toMapWidgetState()
            MapWidgetContent(state = state, context = context)
        }
    }
}

private const val COLOR_ERROR = 0xFFF44336.toInt()      // Red
private const val COLOR_OK = 0xFF4CAF50.toInt()          // Green
private const val COLOR_WARNING = 0xFFFFC107.toInt()     // Amber
private const val COLOR_BACKGROUND = 0xFF1A1A2E.toInt()  // Dark navy
private const val COLOR_SUBTITLE = 0xFF666666.toInt()    // Gray

private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
private const val COLOR_LTGRAY = 0xFFCCCCCC.toInt()

@Composable
private fun MapWidgetContent(state: MapWidgetState, context: Context) {
    val statusColor = when {
        state.hasError -> COLOR_ERROR
        state.packInstalled && state.trafficEventCount > 0 -> COLOR_OK
        state.packInstalled -> COLOR_WARNING
        else -> COLOR_ERROR
    }

    val statusText = when {
        state.hasError -> context.getString(R.string.widget_traffic_error)
        !state.packInstalled -> context.getString(R.string.widget_no_pack)
        state.trafficEventCount > 0 -> context.getString(R.string.widget_traffic_events, state.trafficEventCount)
        else -> context.getString(R.string.widget_traffic_loading)
    }

    val packLabel = if (state.packInstalled) {
        context.getString(R.string.widget_pack_version, state.packVersion ?: "?")
    } else {
        context.getString(R.string.widget_no_pack)
    }

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(COLOR_BACKGROUND))
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = context.getString(R.string.app_name),
            style = TextStyle(
                color = ColorProvider(COLOR_WHITE),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            ),
        )

        Text(
            text = packLabel,
            style = TextStyle(
                color = ColorProvider(COLOR_LTGRAY),
                fontSize = 12.sp,
            ),
            modifier = GlanceModifier.padding(top = 4.dp),
        )

        Row(
            modifier = GlanceModifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "● ",
                style = TextStyle(
            color = ColorProvider(statusColor),
                fontSize = 14.sp,
            ),
        )
        Text(
            text = statusText,
            style = TextStyle(
                color = ColorProvider(COLOR_OK),
                    fontSize = 12.sp,
                ),
            )
        }

        Text(
            text = context.getString(R.string.widget_tap_to_open),
            style = TextStyle(
                color = ColorProvider(COLOR_SUBTITLE),
                fontSize = 10.sp,
            ),
            modifier = GlanceModifier.padding(top = 8.dp),
        )
    }
}

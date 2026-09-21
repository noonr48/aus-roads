package au.com.ausroads.ui.pins

import androidx.annotation.StringRes
import au.com.ausroads.R

/** A persisted pin colour paired with the human name exposed to accessibility services. */
internal data class PinColorChoice(
    val hex: String,
    @StringRes val nameRes: Int,
)

// Avoid traffic-severity hues and the user-location blue so map symbols remain distinct.
internal val PIN_COLOR_CHOICES = listOf(
    PinColorChoice("#1B5E20", R.string.pin_color_forest_green),
    PinColorChoice("#00838F", R.string.pin_color_teal),
    PinColorChoice("#6D4C41", R.string.pin_color_brown),
    PinColorChoice("#C2185B", R.string.pin_color_magenta),
    PinColorChoice("#455A64", R.string.pin_color_slate),
    PinColorChoice("#283593", R.string.pin_color_indigo),
)

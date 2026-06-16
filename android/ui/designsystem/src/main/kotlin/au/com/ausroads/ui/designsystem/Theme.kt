/*
 * Design system theme. The :app module re-exports this for convenience. v0.1 ships with
 * a single Material 3 light/dark theme seeded with the "road" green.
 */
package au.com.ausroads.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AusRoadsColors {
    val Seed = Color(0xFF1B5E20)
    val SeedLight = Color(0xFF4C8C4A)
    val SeedDark = Color(0xFF003300)
    val Warning = Color(0xFFF08C2E)
    val Danger = Color(0xFFD8323C)
    val OnSeed = Color.White
    val OnSeedDark = Color.Black
}

private val LightColors = lightColorScheme(
    primary = AusRoadsColors.Seed,
    onPrimary = AusRoadsColors.OnSeed,
    primaryContainer = AusRoadsColors.SeedLight,
    onPrimaryContainer = AusRoadsColors.OnSeed,
    secondary = AusRoadsColors.SeedLight,
    onSecondary = AusRoadsColors.OnSeed,
    error = AusRoadsColors.Danger,
    onError = AusRoadsColors.OnSeed,
)

private val DarkColors = darkColorScheme(
    primary = AusRoadsColors.SeedLight,
    onPrimary = AusRoadsColors.OnSeedDark,
    primaryContainer = AusRoadsColors.Seed,
    onPrimaryContainer = AusRoadsColors.OnSeed,
    secondary = AusRoadsColors.Seed,
    onSecondary = AusRoadsColors.OnSeedDark,
    error = AusRoadsColors.Danger,
    onError = AusRoadsColors.OnSeedDark,
)

@Composable
fun AusRoadsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

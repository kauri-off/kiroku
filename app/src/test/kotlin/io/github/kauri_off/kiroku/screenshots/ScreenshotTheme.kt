package io.github.kauri_off.kiroku.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun ScreenshotTheme(
    variant: Variant,
    content: @Composable () -> Unit,
) {
    val scheme =
        when {
            variant.altSeed -> provisionalAltScheme
            variant.dark -> darkColorScheme()
            else -> lightColorScheme()
        }
    MaterialTheme(colorScheme = scheme, content = content)
}

// Stands in for the scheme of the second seed, #8B5000, until the app theme generates it.
private val provisionalAltScheme =
    lightColorScheme(
        primary = Color(0xFF8B5000),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDCBE),
        onPrimaryContainer = Color(0xFF2D1600),
        secondary = Color(0xFF725A42),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDCBE),
        onSecondaryContainer = Color(0xFF291806),
        tertiary = Color(0xFF58633A),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDCE8B4),
        onTertiaryContainer = Color(0xFF161E01),
    )

package io.github.kauri_off.kiroku.design.theme

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

enum class ThemeMode { System, Light, Dark }

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
)

@Composable
fun ThemeSettings.isDark(): Boolean =
    when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

@Composable
fun kirokuColorScheme(
    dark: Boolean,
    dynamicColor: Boolean,
    seed: Seed = Seed.Teal,
): ColorScheme {
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    return seed.scheme(dark)
}

// The Material 3 Expressive theme with its own typography, shapes and motion scheme; only the colours are Kiroku's.
@Composable
fun KirokuTheme(
    dark: Boolean,
    dynamicColor: Boolean,
    seed: Seed = Seed.Teal,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(colorScheme = kirokuColorScheme(dark, dynamicColor, seed), typography = ExpressiveTypography, content = content)
}

// Typography() copies the plain roles into the emphasised ones; naming one, at its Expressive value, takes the scale's.
private val ExpressiveTypography =
    Typography().let { plain -> Typography(labelSmallEmphasized = plain.labelSmall.copy(fontWeight = FontWeight.Bold)) }

// The app's window: edge-to-edge, system bar icons that follow the theme, and the theme's background behind it all.
fun ComponentActivity.setKirokuContent(
    settings: ThemeSettings = ThemeSettings(),
    seed: Seed = Seed.Teal,
    content: @Composable () -> Unit,
) {
    enableEdgeToEdge()
    setContent {
        val dark = settings.isDark()
        KirokuTheme(dark, settings.dynamicColor, seed) {
            val scrim =
                MaterialTheme.colorScheme.surfaceContainer
                    .copy(alpha = NAVIGATION_SCRIM_ALPHA)
                    .toArgb()
            DisposableEffect(dark, scrim) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(scrim, scrim) { dark },
                )
                onDispose {}
            }
            Surface(modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}

// Only three-button navigation below Android 10 shows this scrim; gesture navigation has none.
private const val NAVIGATION_SCRIM_ALPHA = 0.8f

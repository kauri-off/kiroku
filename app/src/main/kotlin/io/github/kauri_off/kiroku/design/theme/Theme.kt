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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.DisplayPreferences
import io.github.kauri_off.kiroku.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.graphics.Color as ComposeColor

private val LocalDarkTheme = staticCompositionLocalOf { false }

private val StartupColors =
    darkColorScheme(
        surface = ComposeColor(0xFF181818),
        onSurface = ComposeColor(0xFFE6E6E6),
        primary = ComposeColor(0xFFE6E6E6),
        surfaceContainer = ComposeColor(0xFF242424),
    )

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
)

@Composable
fun ThemeSettings.isDark(): Boolean =
    when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark, ThemeMode.PureBlack -> true
    }

@Composable
fun kirokuColorScheme(
    dark: Boolean,
    dynamicColor: Boolean,
    seed: Seed = Seed.Teal,
    pureBlack: Boolean = false,
): ColorScheme {
    val scheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            seed.scheme(dark)
        }
    return if (pureBlack) {
        scheme.copy(
            background = ComposeColor.Black,
            surface = ComposeColor.Black,
            surfaceDim = ComposeColor.Black,
            surfaceContainerLowest = ComposeColor.Black,
        )
    } else {
        scheme
    }
}

// The Material 3 Expressive theme with its own typography, shapes and motion scheme; only the colours are Kiroku's.
@Composable
fun KirokuTheme(
    dark: Boolean,
    dynamicColor: Boolean,
    seed: Seed = Seed.Teal,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) = KirokuTheme(dark, kirokuColorScheme(dark, dynamicColor, seed, pureBlack), content)

@Composable
private fun KirokuTheme(
    dark: Boolean,
    colors: ColorScheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            typography = ExpressiveTypography,
            content = content,
        )
    }
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
    setContent { KirokuWindow(settings, seed, content) }
}

internal fun ComponentActivity.setKirokuContent(
    preferences: Flow<DisplayPreferences>,
    content: @Composable (display: DisplayPreferences?, hasTheme: Boolean) -> Unit,
) {
    enableEdgeToEdge()
    setContent {
        val display = preferences.collectAsScreenState(initial = null).value
        var savedTheme by rememberSaveable { mutableStateOf<String?>(null) }
        val mode = display?.theme ?: ThemeMode.entries.firstOrNull { it.name == savedTheme }
        SideEffect {
            if (display != null) savedTheme = display.theme.name
        }
        KirokuWindow(mode?.let { ThemeSettings(mode = it) }, Seed.Teal) { content(display, mode != null) }
    }
}

@Composable
internal fun ComponentActivity.KirokuWindow(
    settings: ThemeSettings?,
    seed: Seed,
    content: @Composable () -> Unit,
) {
    val dark = settings?.isDark() ?: true
    val colors =
        if (settings == null) StartupColors else kirokuColorScheme(dark, settings.dynamicColor, seed, settings.mode == ThemeMode.PureBlack)
    KirokuTheme(dark, colors) {
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

@Suppress("DEPRECATION")
@Composable
internal fun DialogSystemBarAppearance() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val dark = LocalDarkTheme.current
    val scrim =
        MaterialTheme.colorScheme.surfaceContainer
            .copy(alpha = NAVIGATION_SCRIM_ALPHA)
            .toArgb()
    SideEffect {
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = true
        } else {
            window.navigationBarColor = scrim
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

// Only three-button navigation below Android 10 shows this scrim; gesture navigation has none.
private const val NAVIGATION_SCRIM_ALPHA = 0.8f

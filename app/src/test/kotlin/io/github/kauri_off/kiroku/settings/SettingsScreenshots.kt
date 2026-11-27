package io.github.kauri_off.kiroku.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.github.kauri_off.kiroku.core.model.DisplayPreferences
import io.github.kauri_off.kiroku.core.model.StartupTab
import io.github.kauri_off.kiroku.core.model.ThemeMode
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.Transition
import io.github.kauri_off.kiroku.screenshots.Variant
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown

class SettingsScreenshots : ScreenshotRegistry() {
    override val content = contentCase(additionalVariants = listOf(Variant.EnBlack)) { Screen() }
    override val cases =
        listOf(
            case("title-language", largeText = true) {
                Screen()
                TitleLanguageDialog(TitleLanguage.Romaji, dismiss = {}, choose = {})
            },
            case("theme", largeText = true, additionalVariants = listOf(Variant.EnBlack)) {
                Screen()
                ThemeDialog(fixtureMode(), dismiss = {}, choose = {})
            },
            case("startup-tab", largeText = true) {
                Screen()
                StartupTabDialog(StartupTab.Search, dismiss = {}, choose = {})
            },
            case("wide", window = WindowSize(840, 600)) { Screen() },
            case("adult-content-on") { Screen(adult = true) },
        )

    override val loading = notShown("Choices are disabled until read. Navigation is available immediately.")
    override val empty = notShown("The rows are fixed, so the screen always has content")
    override val error = notShown("An unreadable choice falls back to its default")

    override val transitions =
        listOf(
            Transition("adult-content-on", start = "content", millis = MotionBounds.CHANGE) { enabled -> Screen(adult = enabled) },
        )
}

@Composable
private fun Screen(adult: Boolean = false) =
    SettingsScreen(
        SettingsState(TitleLanguage.Romaji, adult, true, DisplayPreferences(fixtureMode(), StartupTab.Search)),
        onEvent = {},
        snackbars = remember { SnackbarHostState() },
    )

@Composable
private fun fixtureMode() = if (MaterialTheme.colorScheme.surface == Color.Black) ThemeMode.PureBlack else ThemeMode.System

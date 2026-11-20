package io.github.kauri_off.kiroku.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown

class SettingsScreenshots : ScreenshotRegistry() {
    override val content = contentCase { Screen() }
    override val loading = notShown("It reads nothing: each row is a way to a screen of its own")
    override val empty = notShown("Its rows are fixed, so it is never empty")
    override val error = notShown("It reads nothing, so nothing can fail")
}

@Composable
private fun Screen() = SettingsScreen(onEvent = {}, snackbars = remember { SnackbarHostState() })

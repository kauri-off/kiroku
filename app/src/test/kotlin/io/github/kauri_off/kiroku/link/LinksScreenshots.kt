package io.github.kauri_off.kiroku.link

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown

class LinksScreenshots : ScreenshotRegistry() {
    override val content = contentCase { LinksScreen(onEvent = {}, snackbars = remember { SnackbarHostState() }) }
    override val loading = notShown("The screen loads nothing: its sentence and its button are always the same.")
    override val empty = notShown("The screen shows no data, so it has nothing to be empty of.")
    override val error = notShown("The screen asks for no data, so nothing it shows can fail to load.")
}

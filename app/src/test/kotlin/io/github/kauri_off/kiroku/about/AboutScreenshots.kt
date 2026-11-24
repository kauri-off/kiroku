package io.github.kauri_off.kiroku.about

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.notShown

class AboutScreenshots : ScreenshotRegistry() {
    override val content = contentCase { Screen() }
    override val loading = notShown("The version and links are available on the first frame")
    override val empty = notShown("There is always a version and a list of links")
    override val error = notShown("The screen reads no remote data")

    override val cases =
        listOf(
            case("wide-landscape", window = WindowSize(840, 600), largeText = true) { Screen() },
            case("wide-portrait", window = WindowSize(600, 960)) { Screen() },
        )
}

@Composable
private fun Screen() {
    AboutScreen(
        state = AboutState(stringResource(R.string.app_name), versionName = "0.1.0"),
        onEvent = {},
        snackbars = remember { SnackbarHostState() },
    )
}

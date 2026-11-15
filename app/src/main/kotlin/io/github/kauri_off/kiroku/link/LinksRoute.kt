package io.github.kauri_off.kiroku.link

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.LocalSplashHold
import io.github.kauri_off.kiroku.shell.ListRoot
import io.github.kauri_off.kiroku.shell.ShellNavigator
import kotlinx.serialization.Serializable

@Serializable
data object LinksKey : NavKey

// The screen leaves as the system's setting opens, so that coming back shows what was under it.
@Composable
fun LinksRoute(
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    LinksScreen(
        onEvent = { event ->
            when (event) {
                LinksEvent.Back -> onBack()
                LinksEvent.OpenSettings -> {
                    onBack()
                    context.openLinkSettings()
                }
            }
        },
        snackbars = snackbars,
    )
}

// Once the window has drawn the List alone, the notice is shown over it if due, and recorded as shown.
@Composable
fun LinkNoticeTurn(
    notice: LinkNotice,
    navigator: ShellNavigator,
) {
    val splash = LocalSplashHold.current
    LaunchedEffect(notice, navigator) {
        splash?.awaitDrawn()
        val alone = { navigator.backStack == listOf(ListRoot) }
        if (!alone() || !notice.due() || !alone()) return@LaunchedEffect
        navigator.open(LinksKey)
        notice.shown()
    }
}

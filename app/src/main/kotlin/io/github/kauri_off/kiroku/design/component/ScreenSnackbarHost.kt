package io.github.kauri_off.kiroku.design.component

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

// False for a screen composed under the top one, as during a predictive back gesture.
val LocalOnTop = compositionLocalOf { true }

// The shell's snackbar host in a screen's scaffold, drawn only by the screen on top so a message shows once.
@Composable
fun ScreenSnackbarHost(snackbars: SnackbarHostState) {
    if (LocalOnTop.current) SnackbarHost(snackbars)
}

// The sheets and dialogs open over the screens, under which a snackbar can be neither seen nor tapped.
class MessageCovers {
    private var open by mutableIntStateOf(0)

    val covered: Boolean get() = open > 0

    internal fun opened() {
        open++
    }

    internal fun closed() {
        open--
    }
}

val LocalMessageCovers = staticCompositionLocalOf<MessageCovers?> { null }

// Called by a sheet or a dialog for as long as it is open.
@Composable
fun CoversMessages() {
    val covers = LocalMessageCovers.current ?: return
    DisposableEffect(covers) {
        covers.opened()
        onDispose { covers.closed() }
    }
}

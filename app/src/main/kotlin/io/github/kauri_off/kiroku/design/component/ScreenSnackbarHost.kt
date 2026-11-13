package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import io.github.kauri_off.kiroku.design.theme.AtSystemScale
import io.github.kauri_off.kiroku.design.theme.Spacing
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

// False for a screen composed under the top one, as during a predictive back gesture.
val LocalOnTop = compositionLocalOf { true }

// The shell's snackbar host in a screen's scaffold, drawn only by the screen on top so a message shows once.
@Composable
fun ScreenSnackbarHost(snackbars: SnackbarHostState) {
    if (LocalOnTop.current) SnackbarHost(snackbars) { SwipeableSnackbar(it) }
}

private enum class Swiped { No, ToStart, ToEnd }

// Swiped past half its width or flung either way, a message is dismissed as by its timeout.
@Composable
private fun SwipeableSnackbar(data: SnackbarData) {
    val state = remember(data) { AnchoredDraggableState(Swiped.No) }
    val system = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val scheme = MaterialTheme.motionScheme
    val settle = remember(scheme, system) { AtSystemScale(scheme.fastEffectsSpec<Float>()) { system?.scaleFactor ?: 1f } }
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.first { it != Swiped.No }
        data.dismiss()
    }
    val action = data.visuals.actionLabel
    Snackbar(
        modifier =
            Modifier
                .padding(Spacing.m)
                .onSizeChanged {
                    val width = it.width.toFloat()
                    state.updateAnchors(
                        DraggableAnchors {
                            Swiped.ToStart at -width
                            Swiped.No at 0f
                            Swiped.ToEnd at width
                        },
                    )
                }.anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    enabled = state.settledValue == Swiped.No,
                    flingBehavior = AnchoredDraggableDefaults.flingBehavior(state, AnchoredDraggableDefaults.PositionalThreshold, settle),
                ).offset { IntOffset(state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0, 0) }
                // Gone once swiped off, so the host's shrinking exit does not pull it back into view.
                .drawWithContent { if (state.settledValue == Swiped.No) drawContent() },
        action =
            action?.let {
                {
                    TextButton(
                        onClick = { data.performAction() },
                        colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    ) { Text(it) }
                }
            },
    ) { Text(data.visuals.message) }
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

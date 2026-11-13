package io.github.kauri_off.kiroku.design.component

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import io.github.kauri_off.kiroku.design.theme.AtSystemScale
import io.github.kauri_off.kiroku.design.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

// False for a screen composed under the top one, as during a predictive back gesture.
val LocalOnTop = compositionLocalOf { true }

// The shell's snackbar host in a screen's scaffold, drawn only by the screen on top so a message shows once.
@Composable
fun ScreenSnackbarHost(snackbars: SnackbarHostState) {
    if (LocalOnTop.current) SnackbarHost(snackbars) { SwipeableSnackbar(it) }
}

// A message whose time the host runs itself, so that a finger holding it stops the time; the stock host's would not.
class TimedMessage(
    override val message: String,
    override val actionLabel: String?,
    val time: SnackbarDuration,
) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = SnackbarDuration.Indefinite
}

private enum class Swiped { No, ToStart, ToEnd }

// Swiped past half its width or flung either way, a message leaves the window and is dismissed as by its timeout.
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
    val drags = remember(data) { MutableInteractionSource() }
    val held by drags.collectIsDraggedAsState()
    val time = (data.visuals as? TimedMessage)?.time
    val accessibility = LocalAccessibilityManager.current
    // Let go, the time starts again in full, as after the message was out of sight.
    LaunchedEffect(data, held) {
        if (held || time == null || time == SnackbarDuration.Indefinite) return@LaunchedEffect
        val millis = time.millis(action != null, accessibility)
        delay(millis)
        data.dismiss()
    }
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val direction = LocalLayoutDirection.current
    // Half the message's width, whatever the distance to the window's edge.
    val half = remember(data) { mutableFloatStateOf(0f) }
    val threshold = remember(data) { { _: Float -> half.floatValue } }
    Snackbar(
        modifier =
            Modifier
                .padding(Spacing.m)
                .onGloballyPositioned { at ->
                    val start = at.positionInWindow().x
                    val width = at.size.width
                    half.floatValue = width / 2f
                    // To the window's edge on each side, and its shadow's width past it.
                    val shadow = with(density) { Spacing.m.toPx() }
                    val left = start + width + shadow
                    val right = window.containerSize.width - start + shadow
                    val rtl = direction == LayoutDirection.Rtl
                    state.updateAnchors(
                        DraggableAnchors {
                            Swiped.ToStart at -(if (rtl) right else left)
                            Swiped.No at 0f
                            Swiped.ToEnd at (if (rtl) left else right)
                        },
                    )
                }.anchoredDraggable(
                    state = state,
                    orientation = Orientation.Horizontal,
                    enabled = state.settledValue == Swiped.No,
                    interactionSource = drags,
                    flingBehavior =
                        HeldStill(
                            state,
                            half::floatValue,
                            AnchoredDraggableDefaults.flingBehavior(state, threshold, settle),
                            scheme,
                        ),
                ).offset { IntOffset(state.offset.takeUnless { it.isNaN() }?.roundToInt() ?: 0, 0) },
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

// Foundation sends a release held still to the nearest anchor; half the message's width decides, as when still moving.
private class HeldStill(
    private val state: AnchoredDraggableState<Swiped>,
    private val half: () -> Float,
    private val moving: FlingBehavior,
    private val motionScheme: MotionScheme,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (initialVelocity != 0f) return with(moving) { performFling(initialVelocity) }
        val offset = state.requireOffset()
        val target =
            when {
                offset >= half() -> Swiped.ToEnd
                offset <= -half() -> Swiped.ToStart
                else -> Swiped.No
            }
        var last = offset
        animate(offset, state.anchors.positionOf(target), animationSpec = motionScheme.fastEffectsSpec()) { value, _ ->
            scrollBy(value - last)
            last = value
        }
        return 0f
    }
}

// The stock host's times, lengthened as the accessibility settings ask.
private fun SnackbarDuration.millis(
    hasAction: Boolean,
    accessibility: AccessibilityManager?,
): Long {
    val original = if (this == SnackbarDuration.Long) LONG_MS else SHORT_MS
    return accessibility?.calculateRecommendedTimeoutMillis(
        original,
        containsIcons = true,
        containsText = true,
        containsControls = hasAction,
    ) ?: original
}

private const val SHORT_MS = 4_000L
private const val LONG_MS = 10_000L

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

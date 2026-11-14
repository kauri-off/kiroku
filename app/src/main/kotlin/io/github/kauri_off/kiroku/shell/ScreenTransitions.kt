package io.github.kauri_off.kiroku.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationEventState
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.design.theme.Spacing

// How the screens move on a change (ARCHITECTURE 8.5): a page opened, Back, or a tap across destinations.
enum class Way { Deeper, Up, Across }

// The side toward the end of a line: right, or left in a right-to-left layout.
internal fun end(rtl: Boolean): Float = if (rtl) -1f else 1f

// Where a screen arriving comes from, in short slides: a page opened from the end; anything else in place.
internal fun Way.enter(rtl: Boolean): Float = if (this == Way.Deeper) end(rtl) else 0f

// Where a screen leaving goes, in short slides: toward the start under a page opened, off toward the end on Back.
internal fun Way.exit(
    rtl: Boolean,
    full: Float,
): Float =
    when (this) {
        Way.Deeper -> -end(rtl)
        Way.Up -> full * end(rtl)
        Way.Across -> 0f
    }

// The way a page under a back gesture moves: away from the edge the finger came from.
internal fun gestureSide(
    edge: Int,
    rtl: Boolean,
): Float =
    when (edge) {
        NavigationEvent.EDGE_LEFT -> 1f
        NavigationEvent.EDGE_RIGHT -> -1f
        else -> end(rtl)
    }

// The share of its way a released page has gone, from where it was [left], as its transition's spring runs.
internal fun released(
    left: Float,
    atRelease: Float,
    now: Float,
    commit: Boolean,
): Float =
    when {
        commit && atRelease < 1f -> left + (1f - left) * ((now - atRelease) / (1f - atRelease)).coerceIn(0f, 1f)
        commit -> 1f
        atRelease > 0f -> left * (now / atRelease).coerceIn(0f, 1f)
        else -> 0f
    }

// What moves the screens: the last navigation, or the back gesture held.
internal class ScreenMotion(
    private val navigator: ShellNavigator,
) {
    var gesture: NavigationEventState<*>? = null

    val held: NavigationEvent? get() = (gesture?.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent

    fun enter(rtl: Boolean): Float = if (held != null) 0f else navigator.way.enter(rtl)

    fun exit(
        rtl: Boolean,
        full: Float,
    ): Float = navigator.way.exit(rtl, full)
}

// A screen's sides, fixed when it starts to enter and to leave, so a change reversed mid-flight turns back.
private class Sides(
    var enter: Float,
    var exit: Float = 0f,
    var leaving: Boolean = false,
) {
    var gesture by mutableStateOf<Float?>(null)
    var release by mutableStateOf<Release?>(null)

    // Where the held page was last drawn, as a share of its width: the release goes on from there.
    var left = 0f
}

private class Release(
    val atRelease: Float,
    val commit: Boolean,
)

// A page opened fades in from the end over the one it covers; between destinations the screens fade through.
internal fun fades(motionScheme: MotionScheme): ContentTransform =
    fadeIn(motionScheme.slowEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec())

// Back: the screen it leads to is there at once, under the page, which stays opaque while it moves away.
internal val uncovers: ContentTransform = EnterTransition.None togetherWith ExitTransition.None

// Going to a screen under this one uncovers it, except a tap across destinations; a back gesture's preview and its
// cancellation, which go to a screen that is not the current one, uncover it too.
internal fun AnimatedContentTransitionScope<Scene<NavKey>>.change(
    motionScheme: MotionScheme,
    current: Scene<NavKey>,
    way: Way,
): ContentTransform {
    val under = initialState.previousEntries.any { it.contentKey == targetState.entries.last().contentKey }
    return if (under && (targetState.key != current.key || way != Way.Across)) uncovers else fades(motionScheme)
}

// A screen slides with its change, follows a held back gesture, and takes no input while it leaves.
@Composable
internal fun MovingScreen(
    motion: ScreenMotion,
    content: @Composable () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val transition = LocalNavAnimatedContentScope.current.transition
    val onTop = LocalOnTop.current
    val leaving = transition.targetState == EnterExitState.PostExit
    val settled = transition.currentState == transition.targetState
    val step = with(LocalDensity.current) { Spacing.xxl.toPx() }
    val full = LocalWindowInfo.current.containerSize.width / step
    val held = motion.held
    val sides = remember { Sides(enter = motion.enter(rtl)) }
    if (leaving && !sides.leaving) {
        val gesture = held?.let { gestureSide(it.swipeEdge, rtl) }
        Snapshot.withoutReadObservation {
            sides.gesture = gesture
            sides.release = null
        }
        sides.exit = if (gesture != null) 1f else motion.exit(rtl, full)
    }
    sides.leaving = leaving
    val slide by transition.animateFloat({ MaterialTheme.motionScheme.slowEffectsSpec() }) { state ->
        when (state) {
            EnterExitState.PreEnter -> sides.enter
            EnterExitState.Visible -> 0f
            EnterExitState.PostExit -> sides.exit
        }
    }
    Snapshot.withoutReadObservation {
        if (sides.gesture != null) {
            if (held == null && sides.release == null) sides.release = Release(slide, commit = !onTop)
            if (!leaving && settled) {
                sides.gesture = null
                sides.release = null
            }
        }
    }
    Box(
        Modifier
            .graphicsLayer {
                val side = sides.gesture
                val release = sides.release
                translationX =
                    when {
                        side == null -> slide * step
                        release == null -> (motion.held?.progress ?: sides.left).also { sides.left = it } * size.width * side
                        else -> released(sides.left, release.atRelease, slide, release.commit) * size.width * side
                    }
            }.then(if (leaving) NoInput else Modifier),
    ) { content() }
}

// No touch, key or accessibility action reaches a leaving screen; Back still goes to the activity.
private val NoInput =
    Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.onPreviewKeyEvent { it.key != Key.Back && it.key != Key.Escape }
        .clearAndSetSemantics {}

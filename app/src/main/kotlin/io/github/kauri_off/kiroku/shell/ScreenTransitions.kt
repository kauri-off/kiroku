package io.github.kauri_off.kiroku.shell

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation3.ui.LocalNavAnimatedContentScope

// How a change of screen went: a page opened, back along a stack, or to another destination.
enum class Way { Deeper, Up, Across }

// ARCHITECTURE 8.5: along a stack the Material forward and backward transition, between destinations a fade through.
// A page opened comes from the end of a line by [step] pixels.
internal fun screenChange(
    way: Way,
    motionScheme: MotionScheme,
    step: Int,
    rtl: Boolean,
): ContentTransform {
    val fromEnd = if (rtl) -step else step
    return when (way) {
        Way.Deeper -> slide(motionScheme, fromEnd)
        Way.Up -> slide(motionScheme, -fromEnd)
        Way.Across -> fadeIn(motionScheme.slowEffectsSpec()) togetherWith fadeOut(motionScheme.fastEffectsSpec())
    }
}

// The arriving screen slides in from [from] pixels and fades in; the leaving one goes the same way, faster.
private fun slide(
    motionScheme: MotionScheme,
    from: Int,
): ContentTransform =
    (slideInHorizontally(motionScheme.slowEffectsSpec()) { from } + fadeIn(motionScheme.slowEffectsSpec())) togetherWith
        (slideOutHorizontally(motionScheme.fastEffectsSpec()) { -from } + fadeOut(motionScheme.fastEffectsSpec()))

// A screen that is leaving takes no touch, key or accessibility input until its transition settles.
@Composable
internal fun NoInputWhileLeaving(content: @Composable () -> Unit) {
    val leaving = LocalNavAnimatedContentScope.current.transition.targetState == EnterExitState.PostExit
    Box(if (leaving) NoInput else Modifier) { content() }
}

// Back and Escape still reach the activity.
private val NoInput =
    Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.onPreviewKeyEvent { it.key != Key.Back && it.key != Key.Escape }
        .clearAndSetSemantics {}

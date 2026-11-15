package io.github.kauri_off.kiroku.shell

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.navigation3.ui.LocalNavAnimatedContentScope

// How a change of screen went: a page opened, back along a stack, or to another destination.
enum class Way { Deeper, Up, Across }

// ARCHITECTURE 8.5: forward and backward along a stack by [step] pixels, a fade through between destinations.
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

// A leaving screen takes no touch, key or accessibility input; once it is off the stack and faded, touch passes under it.
@Composable
internal fun NoInputWhileLeaving(
    inStack: Boolean,
    content: @Composable () -> Unit,
) {
    val leaving = LocalNavAnimatedContentScope.current.transition.targetState == EnterExitState.PostExit
    val drawn by animateFloatAsState(if (inStack) 1f else 0f, MaterialTheme.motionScheme.fastEffectsSpec())
    val passes =
        Modifier.graphicsLayer {
            clip = drawn == 0f
            shape = Nowhere
        }
    Box(if (leaving) passes.then(NoInput) else Modifier) { content() }
}

// A clip that holds nothing, so no touch reaches the screen it clips.
private val Nowhere = GenericShape { _, _ -> }

// Back and Escape still reach the activity.
private val NoInput =
    Modifier
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.onPreviewKeyEvent { it.key != Key.Back && it.key != Key.Escape }
        .clearAndSetSemantics {}

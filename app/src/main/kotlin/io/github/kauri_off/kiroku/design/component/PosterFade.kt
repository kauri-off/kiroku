package io.github.kauri_off.kiroku.design.component

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.RememberObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.times
import androidx.compose.ui.unit.toIntSize
import coil3.compose.AsyncImagePainter.State
import coil3.decode.DataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// A poster's states: [placeholder] while loading and on failure, and a picture fetched from the network or the disk
// fading in over what was shown; a picture from memory, or with the system's animations off, is shown at once.
internal class PosterFade(
    private val placeholder: Painter?,
    private val motionScheme: MotionScheme,
    private val scope: CoroutineScope,
) : (State) -> State {
    private var shown: Painter? = null

    override fun invoke(state: State): State {
        val result =
            when (state) {
                is State.Loading -> placeholder?.let { state.copy(painter = it) } ?: state
                is State.Error -> placeholder?.let { state.copy(painter = it) } ?: state
                is State.Success -> if (fades(state)) state.copy(painter = FadingIn(shown, state.painter, motionScheme, scope)) else state
                is State.Empty -> state
            }
        shown = result.painter
        return result
    }

    private fun fades(state: State.Success): Boolean =
        state.result.dataSource in fetched && scope.coroutineContext[MotionDurationScale]?.scaleFactor != 0f

    private companion object {
        val fetched = setOf(DataSource.NETWORK, DataSource.DISK)
    }
}

// [over] at its own size and place, fading in over [under] cropped to the same place; once in, [over] alone.
private class FadingIn(
    private val under: Painter?,
    private val over: Painter,
    private val motionScheme: MotionScheme,
    private val scope: CoroutineScope,
) : Painter(),
    RememberObserver {
    private val shown = Animatable(0f)
    private var running: Job? = null

    override val intrinsicSize get() = over.intrinsicSize

    override fun DrawScope.onDraw() {
        val fraction = shown.value
        if (fraction < 1f) under?.let { drawCropped(it) }
        with(over) { draw(size, alpha = fraction) }
    }

    private fun DrawScope.drawCropped(painter: Painter) {
        val source = painter.intrinsicSize
        if (source.isUnspecified) return with(painter) { draw(size) }
        val scaled = source * ContentScale.Crop.computeScaleFactor(source, size)
        val offset = Alignment.Center.align(scaled.toIntSize(), size.toIntSize(), layoutDirection)
        translate(offset.x.toFloat(), offset.y.toFloat()) { with(painter) { draw(scaled) } }
    }

    override fun onRemembered() {
        if (running != null || shown.value == 1f) return
        running = scope.launch(start = CoroutineStart.UNDISPATCHED) { shown.animateTo(1f, motionScheme.fastEffectsSpec()) }
    }

    override fun onForgotten() {
        running?.cancel()
        running = null
    }

    override fun onAbandoned() = onForgotten()
}

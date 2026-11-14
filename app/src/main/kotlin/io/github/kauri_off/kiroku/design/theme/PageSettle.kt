package io.github.kauri_off.kiroku.design.theme

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.ceil

// ViewPager2's snap, shortened after a fast release, the one motion value not from the theme's scheme (ARCHITECTURE 8.5).
@Immutable
class PageSettle(
    private val density: Float,
) : FiniteAnimationSpec<Float> {
    // ViewPager2's time for the way; a release toward the target shortens it until the curve starts at the finger's speed.
    fun millis(
        distancePx: Float,
        velocityPxPerSecond: Float = 0f,
    ): Int {
        val linear = minOf(MAX_LINEAR_MS, ceil(abs(distancePx) * MS_PER_INCH / (density * DPI_PER_DENSITY)))
        val still = ceil(linear / DECELERATE_SHARE).toInt()
        return if (distancePx == 0f || velocityPxPerSecond * distancePx <= 0f) {
            still
        } else {
            val meeting = ceil(DECELERATE_START * abs(distancePx) / abs(velocityPxPerSecond) * MS_PER_SECOND).toInt()
            meeting.coerceIn(minOf(SHORTEST_MS, still), still)
        }
    }

    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<Float, V>): VectorizedFiniteAnimationSpec<V> =
        object : VectorizedFiniteAnimationSpec<V> {
            private var last: Pair<Int, VectorizedFiniteAnimationSpec<V>>? = null

            private fun of(
                initial: V,
                target: V,
                velocity: V,
            ): VectorizedFiniteAnimationSpec<V> {
                val distance = converter.convertFromVector(target) - converter.convertFromVector(initial)
                val millis = millis(distance, converter.convertFromVector(velocity))
                last?.let { (cached, spec) -> if (cached == millis) return spec }
                return tween<Float>(millis, easing = decelerate).vectorize(converter).also { last = millis to it }
            }

            override fun getDurationNanos(
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = of(initialValue, targetValue, initialVelocity).getDurationNanos(initialValue, targetValue, initialVelocity)

            override fun getValueFromNanos(
                playTimeNanos: Long,
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = of(initialValue, targetValue, initialVelocity).getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)

            override fun getVelocityFromNanos(
                playTimeNanos: Long,
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = of(
                initialValue,
                targetValue,
                initialVelocity,
            ).getVelocityFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
        }

    override fun equals(other: Any?) = other is PageSettle && other.density == density

    override fun hashCode() = density.hashCode()

    private companion object {
        const val MS_PER_INCH = 100f
        const val MAX_LINEAR_MS = 100f
        const val DPI_PER_DENSITY = 160f
        const val MS_PER_SECOND = 1000f

        // The decelerating curve starts at twice its mean speed, so a settle of 2·d/v starts at the release's speed v.
        const val DECELERATE_START = 2f

        // A fast release never makes the settle shorter than this.
        const val SHORTEST_MS = 150

        // RecyclerView's LinearSmoothScroller: a decelerating curve covers this share of its way at its start speed.
        const val DECELERATE_SHARE = 0.3356f

        // Android's DecelerateInterpolator.
        val decelerate = Easing { 1f - (1f - it) * (1f - it) }
    }
}

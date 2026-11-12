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

// The one motion value not from the theme's scheme (ARCHITECTURE 8.5): the List's pages settle after a release and
// slide after a tab tap as ViewPager2's snap moves them. The fling's speed is not carried into the settle.
@Immutable
class PageSettle(
    private val density: Float,
) : FiniteAnimationSpec<Float> {
    // 100 ms an inch, at most 100 ms, stretched by the share of the way a decelerating curve covers at its start speed.
    fun millis(distancePx: Float): Int {
        val linear = minOf(MAX_LINEAR_MS, ceil(abs(distancePx) * MS_PER_INCH / (density * DPI_PER_DENSITY)))
        return ceil(linear / DECELERATE_SHARE).toInt()
    }

    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<Float, V>): VectorizedFiniteAnimationSpec<V> =
        object : VectorizedFiniteAnimationSpec<V> {
            private var last: Pair<Int, VectorizedFiniteAnimationSpec<V>>? = null

            private fun of(
                initial: V,
                target: V,
            ): VectorizedFiniteAnimationSpec<V> {
                val millis = millis(converter.convertFromVector(target) - converter.convertFromVector(initial))
                last?.let { (cached, spec) -> if (cached == millis) return spec }
                return tween<Float>(millis, easing = decelerate).vectorize(converter).also { last = millis to it }
            }

            override fun getDurationNanos(
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = of(initialValue, targetValue).getDurationNanos(initialValue, targetValue, initialVelocity)

            override fun getValueFromNanos(
                playTimeNanos: Long,
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = of(initialValue, targetValue).getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)

            override fun getVelocityFromNanos(
                playTimeNanos: Long,
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = of(initialValue, targetValue).getVelocityFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
        }

    override fun equals(other: Any?) = other is PageSettle && other.density == density

    override fun hashCode() = density.hashCode()

    private companion object {
        const val MS_PER_INCH = 100f
        const val MAX_LINEAR_MS = 100f
        const val DPI_PER_DENSITY = 160f

        // RecyclerView's LinearSmoothScroller: a decelerating curve covers this share of its way at its start speed.
        const val DECELERATE_SHARE = 0.3356f

        // Android's DecelerateInterpolator.
        val decelerate = Easing { 1f - (1f - it) * (1f - it) }
    }
}

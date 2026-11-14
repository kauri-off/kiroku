package io.github.kauri_off.kiroku.design.theme

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.runtime.Immutable

// [spec] at the system's animation scale, for a settle that runs where Compose does not apply it; at 0 it ends at once.
@Immutable
class AtSystemScale<T>(
    private val spec: FiniteAnimationSpec<T>,
    private val scale: () -> Float,
) : FiniteAnimationSpec<T> {
    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<T, V>): VectorizedFiniteAnimationSpec<V> {
        val inner = spec.vectorize(converter)
        return object : VectorizedFiniteAnimationSpec<V> {
            private fun unscaled(
                playTimeNanos: Long,
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ): Long {
                val factor = scale()
                return if (factor > 0f) {
                    (playTimeNanos / factor).toLong()
                } else {
                    inner.getDurationNanos(initialValue, targetValue, initialVelocity)
                }
            }

            override fun getDurationNanos(
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = (inner.getDurationNanos(initialValue, targetValue, initialVelocity) * scale().coerceAtLeast(0f)).toLong()

            override fun getValueFromNanos(
                playTimeNanos: Long,
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = inner.getValueFromNanos(
                unscaled(playTimeNanos, initialValue, targetValue, initialVelocity),
                initialValue,
                targetValue,
                initialVelocity,
            )

            override fun getVelocityFromNanos(
                playTimeNanos: Long,
                initialValue: V,
                targetValue: V,
                initialVelocity: V,
            ) = inner.getVelocityFromNanos(
                unscaled(playTimeNanos, initialValue, targetValue, initialVelocity),
                initialValue,
                targetValue,
                initialVelocity,
            )
        }
    }

    override fun equals(other: Any?) = other is AtSystemScale<*> && other.spec == spec && other.scale === scale

    override fun hashCode() = 31 * spec.hashCode() + scale.hashCode()
}

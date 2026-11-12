package io.github.kauri_off.kiroku.design.theme

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter

// How long this spec takes to carry a value across [distance], to Compose's own threshold.
fun FiniteAnimationSpec<Float>.settleMillis(distance: Float): Long =
    vectorize(Float.VectorConverter)
        .getDurationNanos(AnimationVector1D(0f), AnimationVector1D(distance), AnimationVector1D(0f)) / NANOS_PER_MILLI

private const val NANOS_PER_MILLI = 1_000_000L

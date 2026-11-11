package io.github.kauri_off.kiroku.design.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Easing as AnimationEasing

// The one source of animation values: Material 3's standard motion scheme and its duration and easing tokens, which
// Compose Material 3 keeps internal. Spatial specs move, resize and reshape; effects specs never overshoot.
object Motion {
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, FAST_SPATIAL)

    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, DEFAULT_SPATIAL)

    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(SPATIAL_DAMPING, SLOW_SPATIAL)

    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, FAST_EFFECTS)

    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, DEFAULT_EFFECTS)

    fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(EFFECTS_DAMPING, SLOW_EFFECTS)

    // A change inside a component, a component's entrance and a screen's entrance, each with its exit.
    fun <T> componentChange(): FiniteAnimationSpec<T> = tween(Duration.SHORT4, easing = Easing.standard)

    fun <T> componentEnter(): FiniteAnimationSpec<T> = tween(Duration.MEDIUM2, easing = Easing.emphasizedDecelerate)

    fun <T> componentExit(): FiniteAnimationSpec<T> = tween(Duration.SHORT4, easing = Easing.emphasizedAccelerate)

    fun <T> screenEnter(): FiniteAnimationSpec<T> = tween(Duration.MEDIUM4, easing = Easing.emphasizedDecelerate)

    fun <T> screenExit(): FiniteAnimationSpec<T> = tween(Duration.MEDIUM2, easing = Easing.emphasizedAccelerate)

    object Duration {
        const val SHORT1 = 50
        const val SHORT2 = 100
        const val SHORT3 = 150
        const val SHORT4 = 200
        const val MEDIUM1 = 250
        const val MEDIUM2 = 300
        const val MEDIUM3 = 350
        const val MEDIUM4 = 400
    }

    object Easing {
        val standard: AnimationEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val standardDecelerate: AnimationEasing = CubicBezierEasing(0f, 0f, 0f, 1f)
        val standardAccelerate: AnimationEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
        val emphasizedDecelerate: AnimationEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val emphasizedAccelerate: AnimationEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    }

    private const val SPATIAL_DAMPING = 0.9f
    private const val EFFECTS_DAMPING = 1f
    private const val FAST_SPATIAL = 1400f
    private const val DEFAULT_SPATIAL = 700f
    private const val SLOW_SPATIAL = 300f
    private const val FAST_EFFECTS = 3800f
    private const val DEFAULT_EFFECTS = 1600f
    private const val SLOW_EFFECTS = 800f
}

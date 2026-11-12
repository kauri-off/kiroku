package io.github.kauri_off.kiroku.design

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.settleMillis
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// The settle times of ARCHITECTURE 8.5, in milliseconds: a change inside a component, a component's entrance and a
// screen's entrance, which the theme's fast, default and slow effects springs keep.
object MotionBounds {
    const val CHANGE = 250L
    const val ENTER = 400L
    const val SCREEN = 550L
}

@RunWith(RobolectricTestRunner::class)
class MotionTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private fun scheme(): MotionScheme {
        lateinit var scheme: MotionScheme
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { scheme = MaterialTheme.motionScheme } }
        return scheme
    }

    @Test
    fun `the theme's motion scheme is the expressive one`() {
        assertSame(MotionScheme.expressive(), scheme())
    }

    @Test
    fun `the theme's springs settle within the times of the architecture`() {
        val scheme = scheme()
        val bounds =
            listOf(
                "fast effects" to (scheme.fastEffectsSpec<Float>() to MotionBounds.CHANGE),
                "default effects" to (scheme.defaultEffectsSpec<Float>() to MotionBounds.ENTER),
                "slow effects" to (scheme.slowEffectsSpec<Float>() to MotionBounds.SCREEN),
            )
        for ((name, bound) in bounds) {
            val millis = bound.first.settleMillis(SCREEN_PX)
            assertTrue("$name settles in $millis ms, not within ${bound.second}", millis <= bound.second)
        }
    }

    @Test
    fun `the effects springs the app animates with do not overshoot`() {
        val scheme = scheme()
        for (spec in listOf(scheme.fastEffectsSpec<Float>(), scheme.defaultEffectsSpec(), scheme.slowEffectsSpec())) {
            val vectorized = spec.vectorize(Float.VectorConverter)
            val start = AnimationVector1D(0f)
            val end = AnimationVector1D(SCREEN_PX)
            for (millis in 0..MotionBounds.SCREEN step FRAME) {
                val value = vectorized.getValueFromNanos(millis * 1_000_000L, start, end, start).value
                assertTrue("$value at $millis ms", value <= SCREEN_PX)
            }
        }
    }

    private companion object {
        const val SCREEN_PX = 1080f
        const val FRAME = 16L
    }
}

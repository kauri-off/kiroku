package io.github.kauri_off.kiroku.design

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.PageSettle
import io.github.kauri_off.kiroku.design.theme.settleMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Settle bounds in milliseconds of the fast, default and slow effects springs (ARCHITECTURE 8.5).
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
    fun `the theme's springs settle within their time limits`() {
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

    // ViewPager2's snap at 420 dpi: a page or an inch and more in 298 ms, a shorter way in proportion.
    @Test
    fun `the pager's settle and tab slide are timed as ViewPager2's snap`() {
        val spec = PageSettle(PHONE_DENSITY)
        assertEquals(298L, spec.settleMillis(SCREEN_PX))
        assertEquals(298L, spec.settleMillis(PHONE_DENSITY * INCH_DP))
        assertEquals(155L, spec.settleMillis(SCREEN_PX / 5))
        assertEquals(spec.settleMillis(-SCREEN_PX), spec.settleMillis(SCREEN_PX))
        assertTrue(spec.settleMillis(SCREEN_PX * 4) <= MotionBounds.ENTER)
    }

    // A still release and a tap keep ViewPager2's time; a release toward the target ends sooner, down to 150 ms.
    @Test
    fun `a faster release settles sooner, never sooner than 150 ms and never later than a still one`() {
        val spec = PageSettle(PHONE_DENSITY)
        val way = SCREEN_PX * 2 / 3
        val still = spec.millis(way)
        assertEquals(298, still)
        assertEquals(still, spec.millis(way, 0f))
        assertEquals("a slow release keeps the still time", still, spec.millis(way, dpPerSecond(200f)))
        assertEquals("2·d/v: 720 px at 3000 dp/s", 183, spec.millis(way, dpPerSecond(3000f)))
        assertEquals("the shortest", 150, spec.millis(way, dpPerSecond(9000f)))
        assertEquals("a release away from the target", still, spec.millis(way, -dpPerSecond(3000f)))
        assertEquals("the same leftwards", 183, spec.millis(-way, -dpPerSecond(3000f)))
        assertEquals("a short way stays short", spec.millis(SCREEN_PX / 20), spec.millis(SCREEN_PX / 20, dpPerSecond(9000f)))
        var last = Int.MAX_VALUE
        for (speed in 0..12_000 step 250) {
            val millis = spec.millis(way, dpPerSecond(speed.toFloat()))
            assertTrue("$millis ms at $speed dp/s after $last", millis <= last && millis in 150..still)
            last = millis
        }
    }

    @Test
    fun `the pager's settle decelerates to its end without overshoot and starts at the release's speed`() {
        val vectorized = PageSettle(PHONE_DENSITY).vectorize(Float.VectorConverter)
        val start = AnimationVector1D(0f)
        val end = AnimationVector1D(SCREEN_PX)
        for (release in listOf(0f, dpPerSecond(1500f), dpPerSecond(3000f), dpPerSecond(9000f))) {
            val velocity = AnimationVector1D(release)
            val millis = vectorized.getDurationNanos(start, end, velocity) / 1_000_000L
            var last = 0f
            for (at in 0L..millis step 2) {
                val value = vectorized.getValueFromNanos(at * 1_000_000L, start, end, velocity).value
                assertTrue("$value at $at ms after $release px/s", value >= last && value <= SCREEN_PX)
                last = value
            }
            assertEquals(SCREEN_PX, vectorized.getValueFromNanos(millis * 1_000_000L, start, end, velocity).value, 0f)
            val half = vectorized.getValueFromNanos(millis * 500_000L, start, end, velocity).value
            assertEquals("three quarters of the way at half time", SCREEN_PX * 0.75f, half, SCREEN_PX * 0.01f)
        }
        val meeting = AnimationVector1D(dpPerSecond(3000f))
        val startSpeed = vectorized.getValueFromNanos(2_000_000L, start, end, meeting).value / 0.002f
        assertEquals("the settle starts at the release's speed", meeting.value, startSpeed, meeting.value * 0.02f)
    }

    private fun dpPerSecond(dp: Float) = dp * PHONE_DENSITY

    private companion object {
        const val PHONE_DENSITY = 2.625f
        const val INCH_DP = 160f
        const val SCREEN_PX = 1080f
        const val FRAME = 16L
    }
}

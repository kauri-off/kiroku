package io.github.kauri_off.kiroku.screenshots

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import io.github.kauri_off.kiroku.sample.SampleFixtures
import io.github.kauri_off.kiroku.sample.SampleScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotHarnessTest {
    private val sample: @Composable () -> Unit = { SampleScreen(SampleFixtures.state, onEvent = {}) }

    @Test
    fun windowHasBothSystemBarInsets() {
        var status = -1
        var navigation = -1
        Shot
            .launch(Variant.EnLight) {
                val density = LocalDensity.current
                status = WindowInsets.statusBars.getTop(density)
                navigation = WindowInsets.navigationBars.getBottom(density)
            }.use { shot ->
                assertEquals(shot.dpToPx(SystemBars.HEIGHT_DP).toInt(), status)
                assertEquals(shot.dpToPx(SystemBars.HEIGHT_DP).toInt(), navigation)
            }
    }

    @Test
    fun twoRendersAreIdentical() {
        val first = Shot.launch(Variant.RuStress, content = sample).use { it.image() }
        val second = Shot.launch(Variant.RuStress, content = sample).use { it.image() }
        assertTrue(first.sameAs(second))
    }

    @Test
    fun fullHeightShowsTheLastRowClearOfTheGestureBar() {
        val extra = Shot.launch(Variant.EnLight, content = sample).use { it.scrollRangeDp() }
        assertTrue(extra > 0)
        Shot.launch(Variant.EnLight, Variant.EnLight.heightDp + extra, sample).use { shot ->
            val bottom = shot.scrolledContentBottomPx()
            assertEquals(0, shot.scrollRangeDp())
            assertTrue(bottom <= shot.windowHeightPx - shot.dpToPx(SystemBars.HEIGHT_DP))
            assertTrue(bottom > shot.windowHeightPx - shot.dpToPx(SystemBars.HEIGHT_DP + 2))
        }
    }
}

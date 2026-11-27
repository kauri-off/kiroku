package io.github.kauri_off.kiroku.title

import androidx.activity.ComponentActivity
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import io.github.kauri_off.kiroku.design.MotionBounds
import io.github.kauri_off.kiroku.design.component.LocalRouteImages
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.screenshots.RemoteImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h600dp-xhdpi")
class TitleGalleryMotionTest {
    private var durationScale = 1f
    private val compose =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor get() = durationScale
                },
        )

    @get:Rule
    val rule = registered(compose)

    private val clock get() = compose.mainClock

    @Test
    fun anOppositeTapReversesTheSlideAndThePagerSettles() {
        val pager = gallery()
        clock.autoAdvance = false
        compose.onNodeWithText("Next").performClick()
        clock.advanceTimeBy(FRAME * 5)
        compose.runOnIdle { assertTrue(pager.isScrollInProgress) }

        compose.onNodeWithText("Previous").performClick()
        clock.advanceTimeBy(MotionBounds.ENTER)
        compose.runOnIdle {
            assertEquals(0, pager.settledPage)
            assertFalse(pager.isScrollInProgress)
        }
    }

    @Test
    fun withAnimationsOffNextSelectsThePageImmediately() {
        durationScale = 0f
        val pager = gallery()
        clock.autoAdvance = false
        compose.onNodeWithText("Next").performClick()
        repeat(3) { clock.advanceTimeByFrame() }
        compose.runOnIdle {
            assertEquals(1, pager.settledPage)
            assertFalse(pager.isScrollInProgress)
        }
    }

    private fun gallery(): PagerState {
        val image = ColorImage(RemoteImages.BLOCK, width = RemoteImages.POSTER_WIDTH, height = RemoteImages.POSTER_HEIGHT)
        val loader =
            ImageLoader
                .Builder(compose.activity)
                .components { add(Interceptor { chain -> SuccessResult(image, chain.request, DataSource.MEMORY) }) }
                .build()
        lateinit var pager: PagerState
        val pages = listOf(listOf("cover"), listOf("extra"))
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                CompositionLocalProvider(LocalRouteImages provides loader) {
                    pager = rememberPagerState { pages.size }
                    CoverGallery(pages, "Sample", pager)
                }
            }
        }
        compose.waitForIdle()
        return pager
    }

    private companion object {
        const val FRAME = 16L
    }
}

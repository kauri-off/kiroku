package io.github.kauri_off.kiroku.title

import androidx.activity.ComponentActivity
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.SuccessResult
import io.github.kauri_off.kiroku.core.model.TitlePicture
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
import java.util.concurrent.CopyOnWriteArrayList

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
    fun previousCancelsNextWhileThePagerIsStillAtZeroOffset() {
        val pager = gallery()
        clock.autoAdvance = false
        compose.onNodeWithText("Next").performClick()
        clock.advanceTimeByFrame()
        compose.onNodeWithText("Previous").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { click ->
            assertEquals(1, pager.targetPage)
            assertEquals(0f, pager.currentPageOffsetFraction, 0f)
            assertTrue(click())
        }
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

    @Test
    fun nextFollowsTheRequestedPictureWhenItMovesAndGainsALargeSourceBeforeTheSlide() {
        val pages = mutableStateOf(listOf(COVER, FIRST, SECOND))
        val requested = CopyOnWriteArrayList<Any>()
        val pager = gallery(pages, requested)
        startNextAtZeroOffset(pager)
        compose.runOnIdle { pages.value = listOf(COVER, SECOND, FIRST.copy(large = FIRST_LARGE)) }

        clock.advanceTimeBy(2 * MotionBounds.ENTER)
        clock.autoAdvance = true
        assertPictureDisplayed("Image\u00A03\u00A0of\u00A03\u00A0for Sample")
        assertTrue(FIRST_LARGE in requested)
        compose.runOnIdle {
            assertEquals(2, pager.settledPage)
            assertFalse(pager.isScrollInProgress)
        }
    }

    @Test
    fun nextReturnsToTheSettledPictureIfItsPendingDestinationDisappears() {
        val pages = mutableStateOf(listOf(COVER, FIRST, SECOND))
        val requested = CopyOnWriteArrayList<Any>()
        val pager = gallery(pages, requested)
        startNextAtZeroOffset(pager)
        clock.advanceTimeBy(3 * FRAME)
        compose.runOnIdle {
            assertTrue(pager.currentPageOffsetFraction != 0f)
            pages.value = listOf(COVER, SECOND)
        }

        clock.advanceTimeBy(2 * MotionBounds.ENTER)
        clock.autoAdvance = true
        assertPictureDisplayed("Cover of Sample")
        assertTrue(COVER.medium in requested)
        compose.runOnIdle {
            assertEquals(0, pager.settledPage)
            assertEquals(0f, pager.currentPageOffsetFraction, 0f)
            assertFalse(pager.isScrollInProgress)
        }
    }

    private fun startNextAtZeroOffset(pager: PagerState) {
        clock.autoAdvance = false
        compose.onNodeWithText("Next").performClick()
        clock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1, pager.targetPage)
            assertEquals(0f, pager.currentPageOffsetFraction, 0f)
        }
    }

    private fun assertPictureDisplayed(description: String) {
        val image = hasContentDescription(description)
        compose.waitUntil { compose.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(image).assertIsDisplayed()
    }

    private fun gallery(
        pages: State<List<TitlePicture>> = mutableStateOf(listOf(COVER, FIRST)),
        requested: MutableList<Any> = CopyOnWriteArrayList(),
    ): PagerState {
        val image = ColorImage(RemoteImages.BLOCK, width = RemoteImages.POSTER_WIDTH, height = RemoteImages.POSTER_HEIGHT)
        val loader =
            ImageLoader
                .Builder(compose.activity)
                .components {
                    add(
                        Interceptor { chain ->
                            requested += chain.request.data
                            SuccessResult(image, chain.request, DataSource.MEMORY)
                        },
                    )
                }.build()
        lateinit var pager: PagerState
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                CompositionLocalProvider(LocalRouteImages provides loader) {
                    pager = rememberPagerState { pages.value.size }
                    CoverGallery(pages.value, "Sample", pager)
                }
            }
        }
        compose.waitForIdle()
        return pager
    }

    private companion object {
        const val FRAME = 16L
        const val FIRST_LARGE = "https://example.invalid/gallery/first-large.jpg"
        val COVER = TitlePicture("https://example.invalid/gallery/cover.jpg", null)
        val FIRST = TitlePicture("https://example.invalid/gallery/first.jpg", null)
        val SECOND = TitlePicture("https://example.invalid/gallery/second.jpg", null)
    }
}

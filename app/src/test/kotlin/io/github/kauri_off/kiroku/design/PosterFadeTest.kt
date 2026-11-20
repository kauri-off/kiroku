package io.github.kauri_off.kiroku.design

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import coil3.memory.MemoryCache
import coil3.request.SuccessResult
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.wiring.Pictures
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.BLUE
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.GREEN
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.LARGE_HEIGHT
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.LARGE_WIDTH
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.RED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A picture fetched from the network fades in over what the poster showed, on the theme's fast effects spring
// (ARCHITECTURE 8.5); one in memory is drawn in its first frame.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class PosterFadeTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @get:Rule
    val pictures = Pictures()

    private var placeholder = 0

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        KirokuTheme(dark = false, dynamicColor = false) {
            placeholder = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
            content()
        }

    @Test
    fun `a picture from the network fades in from the placeholder, settles within its time and leaves the screen idle`() {
        val url = pictures.picture("/medium/1.png", RED)
        compose.mainClock.autoAdvance = false
        compose.setContent { Themed { Poster(url, PosterSize.Row, Modifier.testTag(POSTER)) } }
        compose.mainClock.advanceTimeByFrame()
        arrive(url)

        assertEquals(placeholder, centre())
        compose.mainClock.advanceTimeBy(2 * FRAME)
        val midway = centre()
        assertNotEquals(placeholder, midway)
        assertNotEquals(RED, midway)
        assertTrue(Color.red(midway) > Color.red(placeholder) || Color.green(midway) < Color.green(placeholder))
        compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
        assertEquals(RED, centre())
        assertIdle()
    }

    @Test
    fun `a picture already in memory is drawn in its first frame, with nothing to animate`() {
        val url = pictures.picture("/medium/1.png", RED)
        var again by mutableStateOf(false)
        compose.setContent { Themed { key(again) { Poster(url, PosterSize.Row, Modifier.testTag(POSTER)) } } }
        arrive(url)
        assertInMemory(url)

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { applied { again = true } }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(RED, centre())
        assertIdle()
        assertEquals(1, pictures.received.size)
    }

    @Test
    fun `the header's large picture fades in over the row's, cropped in the same place`() {
        val medium = pictures.picture("/medium/1.png", RED, band = GREEN)
        val large = pictures.picture("/large/1.png", BLUE, LARGE_WIDTH, LARGE_HEIGHT, held = true, band = GREEN)
        var header by mutableStateOf(false)
        compose.setContent {
            Themed {
                if (header) {
                    Poster(medium, PosterSize.Header, Modifier.testTag(POSTER), large = large)
                } else {
                    Poster(medium, PosterSize.Row, Modifier.testTag(POSTER))
                }
            }
        }
        arrive(medium)
        assertInMemory(medium)

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { applied { header = true } }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(RED, centre())
        pictures.release()
        arrive(large)
        assertEquals(RED, centre())
        compose.mainClock.advanceTimeBy(2 * FRAME)
        val midway = centre()
        assertTrue(Integer.toHexString(midway), Color.red(midway) in 1..254 && Color.blue(midway) in 1..254 && Color.green(midway) == 0)
        assertEquals(GREEN, top())
        compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
        assertEquals(BLUE, centre())
        assertEquals(GREEN, top())
        assertIdle()
    }

    @Test
    fun `another picture asked for in the middle of a fade ends on that picture`() {
        val first = pictures.picture("/medium/1.png", RED)
        val second = pictures.picture("/medium/2.png", BLUE)
        var url by mutableStateOf(first)
        compose.mainClock.autoAdvance = false
        compose.setContent { Themed { Poster(url, PosterSize.Row, Modifier.testTag(POSTER)) } }
        compose.mainClock.advanceTimeByFrame()
        arrive(first)
        compose.mainClock.advanceTimeBy(2 * FRAME)
        assertNotEquals(RED, centre())

        compose.runOnUiThread { applied { url = second } }
        compose.mainClock.advanceTimeByFrame()
        arrive(second)
        compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
        assertEquals(BLUE, centre())
        assertIdle()
    }

    @OptIn(InternalComposeTracingApi::class)
    @Test
    fun `pictures arriving on the List compose no row and no page`() {
        val entries =
            Fixtures.reference.entries.map { entry ->
                val id = entry.anime.id.value
                entry.copy(anime = entry.anime.copy(posterUrl = pictures.picture("/medium/$id.png", RED, held = true)))
            }
        val composed = mutableMapOf<String, Int>()
        var counting = false
        Composer.setTracer(
            object : CompositionTracer {
                override fun isTraceInProgress() = true

                override fun traceEventStart(
                    key: Int,
                    dirty1: Int,
                    dirty2: Int,
                    info: String,
                ) {
                    if (counting) composed.merge(info.substringBefore(" ("), 1, Int::plus)
                }

                override fun traceEventEnd() = Unit
            },
        )
        try {
            compose.setContent {
                Themed { FixtureListScreen(ListFixtureStates.watching.copy(content = ListFixtureStates.tabs(entries))) }
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            counting = true
            // Every request the rows started; OkHttp sends only five to one host at a time.
            val asked = pictures.asked.size
            pictures.release()
            pictures.await(compose) { pictures.ended.size >= asked }
            compose.mainClock.advanceTimeBy(MotionBounds.CHANGE)
            counting = false
        } finally {
            Composer.setTracer(null)
        }
        assertTrue(pictures.ended.all { it.second is SuccessResult })
        assertEquals("$composed", 0, (composed[ROW] ?: 0) + (composed[PAGE] ?: 0))
        assertIdle()
    }

    private fun arrive(url: String) = pictures.await(compose, url)

    private fun assertInMemory(url: String) = assertNotNull(pictures.loader.memoryCache?.get(MemoryCache.Key(url)))

    private fun applied(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    private fun centre(): Int =
        compose.onNodeWithTag(POSTER).captureToImage().asAndroidBitmap().let {
            it.getPixel(
                it.width / 2,
                it.height / 2,
            )
        }

    private fun top(): Int =
        compose
            .onNodeWithTag(POSTER)
            .captureToImage()
            .asAndroidBitmap()
            .let { it.getPixel(it.width / 2, 1) }

    private fun assertIdle() = assertTrue(Recomposer.runningRecomposers.value.none { it.hasPendingWork })

    companion object {
        const val POSTER = "poster"
        const val FRAME = 16L
        const val ROW = "io.github.kauri_off.kiroku.design.component.AnimeRow"
        const val PAGE = "io.github.kauri_off.kiroku.list.Page"
    }
}

// With the system's animations off, a picture from the network is shown whole in the frame it arrives.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class PosterFadeOffTest {
    private val compose: AndroidComposeTestRule<*, ComponentActivity> =
        createAndroidComposeRule<ComponentActivity>(
            effectContext =
                object : MotionDurationScale {
                    override val scaleFactor = 0f
                },
        )

    @get:Rule
    val rule: TestRule = registered(compose)

    @get:Rule
    val pictures = Pictures()

    @Test
    fun `the picture is complete at once and nothing animates`() {
        val url = pictures.picture("/medium/1.png", RED)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { Poster(url, PosterSize.Row, Modifier.testTag(PosterFadeTest.POSTER)) }
        }
        compose.mainClock.advanceTimeByFrame()
        pictures.await(compose, url)
        val image = compose.onNodeWithTag(PosterFadeTest.POSTER).captureToImage().asAndroidBitmap()
        assertEquals(RED, image.getPixel(image.width / 2, image.height / 2))
        assertTrue(Recomposer.runningRecomposers.value.none { it.hasPendingWork })
    }
}

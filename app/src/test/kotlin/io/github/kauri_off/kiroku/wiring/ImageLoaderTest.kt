package io.github.kauri_off.kiroku.wiring

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.shell.rememberHostShowing
import io.github.kauri_off.kiroku.standin.StandInPosters
import io.github.kauri_off.kiroku.title.TitleFixtures
import io.github.kauri_off.kiroku.title.TitleScreen
import io.github.kauri_off.kiroku.title.TitleTags
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.BLUE
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.GREEN
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.HUGE_HEIGHT
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.HUGE_WIDTH
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.LARGE_HEIGHT
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.LARGE_WIDTH
import io.github.kauri_off.kiroku.wiring.Pictures.Companion.RED
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// Images come through the live graph's HTTP client from a local server that answers with pictures drawn here.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class ImageLoaderTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @get:Rule
    val pictures = Pictures()

    private val context get() = pictures.context
    private val received get() = pictures.received
    private val ended get() = pictures.ended
    private val asked get() = pictures.asked
    private val server get() = pictures.server

    private fun picture(
        path: String,
        colour: Int,
        width: Int = Pictures.MEDIUM_WIDTH,
        height: Int = Pictures.MEDIUM_HEIGHT,
        held: Boolean = false,
        band: Int? = null,
    ) = pictures.picture(path, colour, width, height, held, band)

    private fun failing(path: String) = pictures.failing(path)

    // A change from the test, applied before the next frame.
    private fun applied(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    private fun waitEnded(url: String) = pictures.await(compose, url)

    private fun poster(): Bitmap = compose.onNodeWithTag(POSTER).captureToImage().asAndroidBitmap()

    private fun centre(): Int = poster().let { it.getPixel(it.width / 2, it.height / 2) }

    private fun execute(data: Any): Any = runBlocking { pictures.loader.execute(ImageRequest.Builder(context).data(data).build()) }

    @Test
    fun `a list row asks for the medium picture with the app's user agent, and no token or client id`() {
        val entries =
            Fixtures.reference.entries.map { entry ->
                val id = entry.anime.id.value
                entry.copy(
                    anime =
                        entry.anime.copy(
                            posterUrl = picture("/medium/$id.png", RED),
                            largePosterUrl = picture("/large/$id.png", BLUE, LARGE_WIDTH, LARGE_HEIGHT),
                        ),
                )
            }
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                FixtureListScreen(ListFixtureStates.watching.copy(content = ListFixtureStates.tabs(entries)))
            }
        }
        pictures.await(compose) { ended.size >= 3 }

        assertTrue(received.size >= 3)
        received.forEach { request ->
            assertTrue(request.url.encodedPath, request.url.encodedPath.startsWith("/medium/"))
            assertEquals("Kiroku/${BuildConfig.VERSION_NAME}", request.headers["User-Agent"])
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["X-MAL-CLIENT-ID"])
        }
        assertTrue(ended.all { it.second is SuccessResult })
    }

    @Test
    fun `the title page's header shows the row's picture from its first frame until the large one arrives`() {
        pictures.installed(pictures.calls, StandInPosters)
        val medium = picture("/medium/1.png", RED)
        val large = picture("/large/1.png", BLUE, LARGE_WIDTH, LARGE_HEIGHT, held = true)
        val anime = FixtureAnime.exactToWatch.copy(posterUrl = medium, largePosterUrl = large)
        var opened by mutableStateOf(false)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                if (opened) {
                    TitleScreen(TitleFixtures.of(anime), {}, rememberHostShowing(null))
                } else {
                    AnimeRow(anime.titles.romaji, anime.posterUrl, {})
                }
            }
        }
        waitEnded(medium)

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { applied { opened = true } }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(RED, header(anime))
        compose.waitUntil(5_000) { received.any { it.url.encodedPath == "/large/1.png" } }
        assertEquals(RED, header(anime))

        compose.mainClock.autoAdvance = true
        pictures.release()
        waitEnded(large)
        compose.waitForIdle()
        assertEquals(BLUE, header(anime))
        assertEquals(listOf("/medium/1.png", "/large/1.png"), received.map { it.url.encodedPath })
    }

    // The centre of the header's poster.
    private fun header(anime: Anime): Int {
        val poster = compose.onNodeWithTag(TitleTags.poster).getBoundsInRoot()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        return with(
            compose.density,
        ) { image.getPixel(((poster.left + poster.right) / 2).roundToPx(), ((poster.top + poster.bottom) / 2).roundToPx()) }
    }

    @Test
    fun `a large picture that fails leaves the row's picture in the header`() {
        val medium = picture("/medium/1.png", RED)
        val large = failing("/large/1.png")
        var header by mutableStateOf(false)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                if (header) {
                    Poster(medium, PosterSize.Header, Modifier.testTag(POSTER), large = large)
                } else {
                    Poster(medium, PosterSize.Row, Modifier.testTag(POSTER))
                }
            }
        }
        waitEnded(medium)
        compose.runOnUiThread { applied { header = true } }
        waitEnded(large)
        compose.waitForIdle()
        assertTrue(ended.single { it.first == large }.second is ErrorResult)
        assertEquals(RED, centre())
    }

    @Test
    fun `a failed picture leaves the placeholder and is asked for once`() {
        val url = failing("/medium/1.png")
        var placeholder = 0
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                placeholder = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
                Poster(url, PosterSize.Row, Modifier.testTag(POSTER))
            }
        }
        waitEnded(url)
        compose.waitForIdle()
        assertEquals(placeholder, centre())
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals(1, received.size)
    }

    @Test
    fun `a fling through a long list decodes each picture at the row's size, never at its own`() {
        val urls = (1..60).map { picture("/medium/$it.png", RED, HUGE_WIDTH, HUGE_HEIGHT) }
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                LazyColumn(Modifier.testTag(LIST)) {
                    items(urls.size) { AnimeRow("Title $it", urls[it], {}) }
                }
            }
        }
        for (index in 10 until urls.size step 10) {
            compose.onNodeWithTag(LIST).performScrollToIndex(index)
        }
        pictures.await(compose) { ended.count { it.second is SuccessResult } >= 3 }
        compose.waitForIdle()

        val width = with(compose.density) { Sizes.posterRow.roundToPx() }
        val height = with(compose.density) { Sizes.posterRowHeight.roundToPx() }
        assertTrue(asked.isNotEmpty())
        asked.forEach { assertEquals(Size(width, height), it.second) }
        val decoded = ended.mapNotNull { (it.second as? SuccessResult)?.image }
        assertTrue(decoded.isNotEmpty())
        decoded.forEach { assertTrue("${it.width}×${it.height}", it.width <= width * 1.1f && it.height <= height * 1.1f) }
        val cache = checkNotNull(pictures.loader.memoryCache)
        cache.keys.forEach { key ->
            val image = checkNotNull(cache[key]).image
            assertTrue("${image.width}×${image.height}", image.width <= width * 1.1f && image.height <= height * 1.1f)
        }
    }

    @Test
    fun `without an HTTP client no image reaches a host`() {
        pictures.installed(calls = null)
        val url = picture("/medium/1.png", RED)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { Poster(url, PosterSize.Row, Modifier.testTag(POSTER)) }
        }
        waitEnded(url)
        assertTrue(ended.single().second is ErrorResult)
        assertEquals(0, server.requestCount)
        assertNull(pictures.loader.memoryCache?.get(MemoryCache.Key(url)))
    }

    @Test
    fun `opened with nothing in memory, a failed large picture gives way to the medium one, and then to the placeholder`() {
        val medium = picture("/medium/1.png", RED)
        val large = failing("/large/1.png")
        val lost = failing("/medium/2.png")
        val lostLarge = failing("/large/2.png")
        var which by mutableStateOf(1)
        var placeholder = 0
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                placeholder = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
                if (which == 1) {
                    Poster(medium, PosterSize.Header, Modifier.testTag(POSTER), large = large)
                } else {
                    Poster(lost, PosterSize.Header, Modifier.testTag(POSTER), large = lostLarge)
                }
            }
        }
        waitEnded(medium)
        compose.waitForIdle()
        assertTrue(pictures.result(large) is ErrorResult)
        assertEquals(RED, centre())

        compose.runOnUiThread { applied { which = 2 } }
        waitEnded(lost)
        compose.waitForIdle()
        assertEquals(placeholder, centre())
        assertEquals(listOf("/large/1.png", "/medium/1.png", "/large/2.png", "/medium/2.png"), received.map { it.url.encodedPath })
    }

    @Test
    fun `the header's large picture is decoded at the header's size and cropped as the row's picture was`() {
        val medium = picture("/medium/1.png", RED, band = GREEN)
        val large = picture("/large/1.png", BLUE, HUGE_WIDTH, HUGE_HEIGHT, band = GREEN)
        var header by mutableStateOf(false)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                if (header) {
                    Poster(medium, PosterSize.Header, Modifier.testTag(POSTER), large = large)
                } else {
                    Poster(medium, PosterSize.Row, Modifier.testTag(POSTER))
                }
            }
        }
        waitEnded(medium)
        compose.runOnUiThread { applied { header = true } }
        waitEnded(large)
        compose.waitForIdle()

        val width = with(compose.density) { Sizes.posterHeader.roundToPx() }
        val height = with(compose.density) { (Sizes.posterHeader * 3 / 2).roundToPx() }
        assertEquals(listOf(Size(width, height)), asked.filter { it.first == large }.map { it.second })
        val image = (pictures.result(large) as SuccessResult).image
        assertTrue("${image.width}×${image.height}", image.width <= width * 1.1f && image.height <= height * 1.1f)
        val shown = poster()
        assertEquals(GREEN, shown.getPixel(shown.width / 2, 1))
        assertEquals(BLUE, shown.getPixel(shown.width / 2, shown.height / 2))
    }

    @Test
    fun `only https addresses are fetched, and a planted file, content, resource, data or http address leaves the placeholder`() {
        pictures.installed(pictures.calls, StandInPosters, schemes = Https)
        val file = File(context.cacheDir, "planted.png")
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, file.outputStream())
        val planted =
            listOf(
                "file://${file.absolutePath}",
                file.absolutePath,
                "content://media/external/images/media/1",
                "android.resource://${context.packageName}/drawable/ic_launcher_foreground",
                "file:///android_asset/planted.png",
                "data:image/png;base64,iVBORw0KGgo=",
                "ftp://example.invalid/planted.png",
                picture("/medium/1.png", RED),
            )
        for (address in planted) {
            val result = execute(address)
            assertTrue(address, result is ErrorResult && result.throwable is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
        assertTrue(execute(checkNotNull(FixtureAnime.exactToWatch.posterUrl)) is SuccessResult)
    }

    // The loader the application itself builds: in a build without a client id it is the demo's, which reaches no host.
    @Test
    fun `the application's own loader refuses a host's picture and reaches no host`() {
        SingletonImageLoader.reset()
        val app = SingletonImageLoader.get(context)
        val url = picture("/medium/1.png", RED)
        assertTrue(runBlocking { app.execute(ImageRequest.Builder(context).data(url).build()) } is ErrorResult)
        assertEquals(0, server.requestCount)
        val standIn = runBlocking { app.execute(ImageRequest.Builder(context).data(FixtureAnime.exactToWatch.posterUrl).build()) }
        assertTrue(standIn is SuccessResult)
    }

    private companion object {
        const val POSTER = "poster"
        const val LIST = "list"
    }
}

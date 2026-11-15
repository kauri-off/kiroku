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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.imageDecoderEnabled
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.design.component.AnimeRow
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.shell.rememberHostShowing
import io.github.kauri_off.kiroku.standin.StandInPosters
import io.github.kauri_off.kiroku.title.TitleFixtures
import io.github.kauri_off.kiroku.title.TitleScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Images come through the live graph's HTTP client from a local server that answers with pictures drawn here.
@OptIn(DelicateCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class ImageLoaderTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val context get() = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()
    private val received = CopyOnWriteArrayList<RecordedRequest>()
    private val answers = mutableMapOf<String, () -> MockResponse>()
    private val release = CountDownLatch(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ended = CopyOnWriteArrayList<Pair<String, Any>>()
    private val asked = CopyOnWriteArrayList<Size>()
    private lateinit var loader: ImageLoader
    private var calls: okhttp3.Call.Factory? = null

    @Before
    fun start() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received += request
                    return answers[request.url.encodedPath]?.invoke() ?: MockResponse.Builder().code(404).build()
                }
            }
        server.start()
        val device =
            object : Device {
                override fun isOnline() = true

                override fun observeOnline() = flowOf(true)

                override fun isMainThread() = false
            }
        // MAL's API is elsewhere, so the image host is not the API's and gets no client id.
        val source = LiveSource("images", MalHosts(api = "http://localhost:9/v2/", signIn = "http://localhost:9/v1/oauth2/"))
        val graph = LiveGraph(LiveParts(context, source, CLIENT_ID, Clock.systemUTC(), scope, { throw it }, device = device))
        calls = graph.images
        loader = installed(calls)
    }

    @After
    fun stop() {
        release.countDown()
        SingletonImageLoader.reset()
        scope.cancel()
        server.close()
    }

    // The app's loader, telling the test the size each request asked for and how it ended. Robolectric has no
    // ImageDecoder, so pictures are decoded by BitmapFactory, which Coil sizes by the same rule.
    private fun installed(
        calls: okhttp3.Call.Factory?,
        standIn: Interceptor? = null,
    ): ImageLoader {
        val listener =
            object : coil3.EventListener() {
                override fun resolveSizeEnd(
                    request: ImageRequest,
                    size: Size,
                ) {
                    asked += size
                }

                override fun onSuccess(
                    request: ImageRequest,
                    result: SuccessResult,
                ) {
                    ended += request.data.toString() to result
                }

                override fun onError(
                    request: ImageRequest,
                    result: ErrorResult,
                ) {
                    ended += request.data.toString() to result
                }
            }
        return imageLoader(context, calls, standIn)
            .newBuilder()
            .eventListener(listener)
            .imageDecoderEnabled(false)
            .build()
            .also { SingletonImageLoader.setUnsafe(it) }
    }

    private fun picture(
        path: String,
        colour: Int,
        width: Int = MEDIUM_WIDTH,
        height: Int = MEDIUM_HEIGHT,
        held: Boolean = false,
    ): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        answers[path] = {
            if (held) release.await(10, TimeUnit.SECONDS)
            MockResponse
                .Builder()
                .addHeader("Content-Type", "image/png")
                .body(Buffer().write(png))
                .build()
        }
        return server.url(path).toString()
    }

    private fun failing(path: String): String {
        answers[path] = { MockResponse.Builder().code(500).build() }
        return server.url(path).toString()
    }

    // A change from the test, applied before the next frame.
    private fun applied(change: () -> Unit) {
        change()
        Snapshot.sendApplyNotifications()
    }

    private fun waitEnded(url: String) = compose.waitUntil(5_000) { ended.any { it.first == url } }

    private fun centre(): Int {
        val image = compose.onNodeWithTag(POSTER).captureToImage().asAndroidBitmap()
        return image.getPixel(image.width / 2, image.height / 2)
    }

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
        compose.waitUntil(5_000) { ended.size >= 3 }

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
        loader = installed(calls, StandInPosters)
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
        release.countDown()
        waitEnded(large)
        compose.waitForIdle()
        assertEquals(BLUE, header(anime))
        assertEquals(listOf("/medium/1.png", "/large/1.png"), received.map { it.url.encodedPath })
    }

    // The centre of the header's poster, which stands before the title's heading.
    private fun header(anime: Anime): Int {
        val title = compose.onNode(isHeading() and hasText(anime.titles.romaji)).getBoundsInRoot()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        return with(compose.density) {
            image.getPixel(
                (title.left - Spacing.l - Sizes.posterHeader / 2).roundToPx(),
                (title.top + Sizes.posterHeader * 3 / 4).roundToPx(),
            )
        }
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
        compose.waitUntil(5_000) { ended.count { it.second is SuccessResult } >= 3 }
        compose.waitForIdle()

        val width = with(compose.density) { Sizes.posterRow.roundToPx() }
        val height = with(compose.density) { Sizes.posterRowHeight.roundToPx() }
        assertTrue(asked.isNotEmpty())
        asked.forEach { assertEquals(Size(width, height), it) }
        val decoded = ended.mapNotNull { (it.second as? SuccessResult)?.image }
        assertTrue(decoded.isNotEmpty())
        decoded.forEach { assertTrue("${it.width}×${it.height}", it.width <= width * 1.1f && it.height <= height * 1.1f) }
        val cache = checkNotNull(loader.memoryCache)
        cache.keys.forEach { key ->
            val image = checkNotNull(cache[key]).image
            assertTrue("${image.width}×${image.height}", image.width <= width * 1.1f && image.height <= height * 1.1f)
        }
    }

    @Test
    fun `without an HTTP client no image reaches a host`() {
        loader = installed(calls = null)
        val url = picture("/medium/1.png", RED)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { Poster(url, PosterSize.Row, Modifier.testTag(POSTER)) }
        }
        waitEnded(url)
        assertTrue(ended.single().second is ErrorResult)
        assertEquals(0, server.requestCount)
        assertNull(loader.memoryCache?.get(MemoryCache.Key(url)))
    }

    private companion object {
        const val CLIENT_ID = "the-client-id-5d2b"
        const val POSTER = "poster"
        const val LIST = "list"
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val MEDIUM_WIDTH = 225
        const val MEDIUM_HEIGHT = 318
        const val LARGE_WIDTH = 425
        const val LARGE_HEIGHT = 600
        const val HUGE_WIDTH = 2250
        const val HUGE_HEIGHT = 3180
    }
}

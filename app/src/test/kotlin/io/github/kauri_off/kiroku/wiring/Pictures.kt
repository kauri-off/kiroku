package io.github.kauri_off.kiroku.wiring

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeTestRule
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.imageDecoderEnabled
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okio.Buffer
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor

// A local server answering with pictures drawn here, and the app's loader over the live graph's client installed as
// the singleton, telling the test the size each request asked for and how it ended. The server speaks http, which the
// loader is told to allow; Robolectric has no ImageDecoder, so BitmapFactory decodes, sized by Coil's same rule.
@OptIn(DelicateCoilApi::class)
class Pictures : ExternalResource() {
    val context get() = RuntimeEnvironment.getApplication()
    val server = MockWebServer()
    val received = CopyOnWriteArrayList<RecordedRequest>()
    val ended = CopyOnWriteArrayList<Pair<String, ImageResult>>()
    val asked = CopyOnWriteArrayList<Pair<String, Size>>()
    private val answers = mutableMapOf<String, () -> MockResponse>()
    private val release = CountDownLatch(1)
    private val offMain = CopyOnWriteArrayList<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var calls: Call.Factory
    lateinit var loader: ImageLoader

    override fun before() {
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

    override fun after() {
        checkOnMain()
        release.countDown()
        SingletonImageLoader.reset()
        scope.cancel()
        server.close()
    }

    fun installed(
        calls: Call.Factory?,
        standIn: Interceptor? = null,
        schemes: Set<String> = Https + "http",
    ): ImageLoader {
        val listener =
            object : coil3.EventListener() {
                override fun resolveSizeEnd(
                    request: ImageRequest,
                    size: Size,
                ) {
                    asked += request.data.toString() to size
                }

                override fun onSuccess(
                    request: ImageRequest,
                    result: SuccessResult,
                ) {
                    ended(request, result)
                }

                override fun onError(
                    request: ImageRequest,
                    result: ErrorResult,
                ) {
                    ended(request, result)
                }
            }
        return imageLoaderBuilder(context, calls, MainHandover(standIn), schemes)
            .eventListener(listener)
            .imageDecoderEnabled(false)
            .build()
            .also {
                SingletonImageLoader.setUnsafe(it)
                loader = it
            }
    }

    // A picture of one colour, with a band of [band] across its top tenth when given; [held] waits for [release].
    fun picture(
        path: String,
        colour: Int,
        width: Int = MEDIUM_WIDTH,
        height: Int = MEDIUM_HEIGHT,
        held: Boolean = false,
        band: Int? = null,
    ): String {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        band?.let { Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height / 10f, Paint().apply { color = it }) }
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

    fun failing(path: String): String {
        answers[path] = { MockResponse.Builder().code(500).build() }
        return server.url(path).toString()
    }

    private fun ended(
        request: ImageRequest,
        result: ImageResult,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) offMain += "${request.data}"
        ended += request.data.toString() to result
    }

    private fun checkOnMain() = check(offMain.isEmpty()) { "Ended off the main thread, before the screen had them: $offMain" }

    fun release() = release.countDown()

    fun hasEnded(url: String) = ended.any { it.first == url }

    // Waits for [done] while the main looper runs, where a result reaches the screen and the test in one task.
    fun await(
        compose: ComposeTestRule,
        done: () -> Boolean,
    ) = compose.waitUntil(5_000) {
        compose.waitForIdle()
        checkOnMain()
        done()
    }

    fun await(
        compose: ComposeTestRule,
        url: String,
    ) = await(compose) { hasEnded(url) }

    fun result(url: String) = ended.single { it.first == url }.second

    // Coil runs a poster unconfined under Compose's test interceptor; its result returns to the main thread, as in the app.
    private class MainHandover(
        private val standIn: Interceptor?,
    ) : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val result = standIn?.intercept(chain) ?: chain.proceed()
            if (currentCoroutineContext()[ContinuationInterceptor] != Dispatchers.Unconfined) return result
            return withContext(Dispatchers.Main.immediate) { result }
        }
    }

    companion object {
        const val CLIENT_ID = "the-client-id-5d2b"
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val MEDIUM_WIDTH = 225
        const val MEDIUM_HEIGHT = 318
        const val LARGE_WIDTH = 425
        const val LARGE_HEIGHT = 600
        const val HUGE_WIDTH = 2250
        const val HUGE_HEIGHT = 3180
    }
}

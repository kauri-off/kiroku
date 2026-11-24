package io.github.kauri_off.kiroku.wiring

import android.graphics.Bitmap
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.imageDecoderEnabled
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.InMemoryNotificationStore
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.NotificationStore
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.design.component.routeImageLoader
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.shell.ListRoot
import io.github.kauri_off.kiroku.shell.ProfileRoot
import io.github.kauri_off.kiroku.shell.SearchRoot
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.shell.ShellNavigator
import io.github.kauri_off.kiroku.shell.ShellRoute
import io.github.kauri_off.kiroku.shell.SignOutTurns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageSignOutTest {
    @get:Rule(order = AppEnds.OUTERMOST)
    val appEnds = AppEnds()

    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val context get() = RuntimeEnvironment.getApplication()
    private val work = SupervisorJob()
    private val scope = process()
    private val png by lazy {
        ByteArrayOutputStream()
            .also { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }

    private fun process() = CoroutineScope(SupervisorJob(work) + Dispatchers.Default)

    private fun parts(
        source: String,
        process: CoroutineScope = scope,
        answer: (Interceptor.Chain) -> ResponseBody = { png.toResponseBody(PNG) },
    ): LiveParts {
        val device =
            object : Device {
                override fun isOnline() = true

                override fun observeOnline() = flowOf(true)

                override fun isMainThread() = false
            }
        val standIn =
            Interceptor { chain ->
                check(chain.request().url.host == "images.invalid")
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/png")
                    .body(answer(chain))
                    .build()
            }
        return LiveParts(context, LiveSource(source, MalHosts.Real, standIn), "", Clock.systemUTC(), process, { throw it }, device = device)
    }

    private fun graph(
        parts: LiveParts,
        files: FileSystem = FileSystem.SYSTEM,
    ) = LiveGraph(parts, listStore = { InMemoryLocalList() }, imageFiles = files)

    private fun LiveParts.seedSession() = tokenFile.writeBytes(byteArrayOf(0) + SIGN_IN.value.encodeToByteArray())

    private suspend fun LiveGraph.loader(): ImageLoader = imageLoaderFor(session.observeSession().first()).withFixtureDecoder()

    private fun ImageLoader.withFixtureDecoder() = newBuilder().imageDecoderEnabled(false).build()

    private suspend fun ImageLoader.execute(url: String) = execute(ImageRequest.Builder(context).data(url).build())

    private suspend fun ImageLoader.load(url: String): SuccessResult = execute(url) as SuccessResult

    private fun ImageLoader.closeCache() {
        shutdown()
        diskCache?.shutdown()
    }

    private suspend fun awaitMissing(directory: Path) {
        withTimeout(5_000) {
            while (FileSystem.SYSTEM.exists(directory)) delay(10)
        }
    }

    @After
    fun endWork() = runBlocking { work.cancelAndJoin() }

    @Test
    fun `sign-out cancels the streaming call and removes tokens before the old body ends`() =
        runBlocking {
            val streaming = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val releaseBody = CountDownLatch(1)
            val first = AtomicBoolean(true)
            val parts =
                parts("account") { chain ->
                    if (chain.request().url.toString() == HELD && first.getAndSet(false)) {
                        object : ResponseBody() {
                            private val input =
                                object : Source {
                                    private val bytes = Buffer().write(png)

                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long {
                                        streaming.complete(Unit)
                                        while (!chain.call().isCanceled() && !releaseBody.await(20, TimeUnit.MILLISECONDS)) {
                                            check(!Thread.currentThread().isInterrupted)
                                        }
                                        if (chain.call().isCanceled()) cancelled.complete(Unit)
                                        check(releaseBody.await(15, TimeUnit.SECONDS))
                                        return bytes.read(sink, byteCount)
                                    }

                                    override fun timeout() = Timeout.NONE

                                    override fun close() = Unit
                                }.buffer()

                            override fun contentLength() = png.size.toLong()

                            override fun contentType() = PNG

                            override fun source() = input
                        }
                    } else {
                        png.toResponseBody(PNG)
                    }
                }
            parts.seedSession()
            val graph = graph(parts)
            val loader = graph.loader()
            val oldDirectory = checkNotNull(loader.diskCache).directory
            val other = graph(parts("sandbox")).loader()
            val settings = NotificationSettings(on = false, alsoPlanned = true)
            graph.preferences.writeNotificationSettings(settings)
            assertEquals(DataSource.NETWORK, other.load(CACHED).dataSource)
            assertEquals(DataSource.NETWORK, loader.load(CACHED).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, loader.load(CACHED).dataSource)
            assertTrue(checkNotNull(loader.diskCache).size > 0)
            val held = async { loader.execute(HELD) }
            try {
                withTimeout(5_000) {
                    streaming.await()
                    graph.session.signOut()
                    cancelled.await()
                }
                assertEquals(Session.SignedOut, graph.session.observeSession().first())
                assertEquals(0L, parts.tokenFile.length())
                assertEquals(settings, graph.preferences.observeNotificationSettings().first())
                assertFalse(held.isCompleted)
                val fresh = graph.loader()
                try {
                    assertNotEquals(oldDirectory, fresh.diskCache?.directory)
                    assertEquals(DataSource.NETWORK, fresh.load(HELD).dataSource)
                    releaseBody.countDown()
                    withTimeout(5_000) { held.join() }
                    awaitMissing(oldDirectory)
                    assertTrue(checkNotNull(loader.memoryCache).keys.isEmpty())
                    assertEquals(DataSource.MEMORY_CACHE, fresh.load(HELD).dataSource)
                    assertEquals(DataSource.NETWORK, fresh.load(CACHED).dataSource)
                    assertEquals(DataSource.MEMORY_CACHE, other.load(CACHED).dataSource)
                } finally {
                    fresh.closeCache()
                }
            } finally {
                releaseBody.countDown()
                held.cancel()
                other.closeCache()
            }
        }

    @Test
    fun `a stopped shell keeps its retired loader and restarted routes can cache`() {
        val parts = parts("route")
        parts.seedSession()
        val graph = graph(parts)
        val navigator = ShellNavigator.inMemory()
        val messages = ShellMessages(scope, graph.list)
        val snackbars = SnackbarHostState()
        var captured: ImageLoader? = null
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val session = SignOutTurns(graph, navigator, messages)
                ShellRoute(
                    navigator = navigator,
                    snackbars = snackbars,
                    images = session?.let(graph::imageLoaderFor),
                    entries = { _, _ ->
                        entry<ListRoot> {
                            val loader = routeImageLoader()
                            SideEffect { captured = loader }
                        }
                        entry<SearchRoot> {}
                        entry<ProfileRoot> {}
                    },
                )
            }
        }
        val old = graph.imageLoaderFor(Session.Expired(SIGN_IN))
        compose.waitUntil(5_000) { captured === old }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        runBlocking {
            graph.session.signOut()
            assertEquals(Session.SignedOut, graph.session.observeSession().first())
            assertEquals(0, navigator.restarts)
            val late = runCatching { checkNotNull(captured).withFixtureDecoder().execute(CACHED) }
            assertTrue(late.exceptionOrNull() is CancellationException)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val fresh = graph.imageLoaderFor(Session.SignedOut)
        compose.waitUntil(5_000) { navigator.restarts == 1 && captured === fresh }
        runBlocking {
            val loader = checkNotNull(captured).withFixtureDecoder()
            try {
                assertEquals(DataSource.NETWORK, loader.load(CACHED).dataSource)
                assertEquals(DataSource.MEMORY_CACHE, loader.load(CACHED).dataSource)
            } finally {
                loader.closeCache()
            }
        }
    }

    @Test
    fun `failed recursive deletion quarantines old files across restart and retries`(): Unit =
        runBlocking {
            val firstProcess = process()
            val initial = parts("restarted", firstProcess)
            initial.seedSession()
            val oldLoader = graph(initial).loader()
            assertEquals(DataSource.NETWORK, oldLoader.load(CACHED).dataSource)
            val oldDirectory = checkNotNull(oldLoader.diskCache).directory
            oldLoader.closeCache()
            val orphan = oldDirectory / "unindexed"
            FileSystem.SYSTEM.write(orphan) { write(png) }
            firstProcess.coroutineContext.job.cancelAndJoin()

            val failed = Channel<Unit>(Channel.UNLIMITED)
            val denyDelete =
                object : ForwardingFileSystem(FileSystem.SYSTEM) {
                    override fun delete(
                        path: Path,
                        mustExist: Boolean,
                    ) {
                        if (path.parent == oldDirectory || path == oldDirectory) {
                            failed.trySend(Unit)
                            throw IOException("fixture deletion failure")
                        }
                        super.delete(path, mustExist)
                    }
                }
            val secondProcess = process()
            val signOut = parts("restarted", secondProcess)
            val reused = graph(signOut).loader()
            assertEquals(DataSource.DISK, reused.load(CACHED).dataSource)
            reused.closeCache()
            secondProcess.coroutineContext.job.cancelAndJoin()

            val signOutProcess = process()
            graph(parts("restarted", signOutProcess), denyDelete).session.signOut()
            withTimeout(5_000) { failed.receive() }
            assertEquals(0L, signOut.tokenFile.length())
            assertTrue(FileSystem.SYSTEM.exists(orphan))
            signOutProcess.coroutineContext.job.cancelAndJoin()

            val thirdProcess = process()
            val quarantined = graph(parts("restarted", thirdProcess), denyDelete)
            assertEquals(Session.SignedOut, quarantined.session.observeSession().first())
            quarantined.loader().apply {
                assertNotEquals(oldDirectory, diskCache?.directory)
                assertEquals(DataSource.NETWORK, load(CACHED).dataSource)
                closeCache()
            }
            withTimeout(5_000) { failed.receive() }
            assertTrue(FileSystem.SYSTEM.exists(orphan))
            thirdProcess.coroutineContext.job.cancelAndJoin()

            graph(parts("restarted")).loader().apply {
                awaitMissing(oldDirectory)
                assertEquals(DataSource.DISK, load(CACHED).dataSource)
                closeCache()
            }
        }

    @Test
    fun `overlapping sign-out does not run a second cleanup or retire the fresh loader`() =
        runBlocking {
            val reading = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val parts = parts("concurrent")
            parts.seedSession()
            val store =
                object : NotificationStore by InMemoryNotificationStore() {
                    override suspend fun clear(owner: SignInId?) {
                        reading.complete(Unit)
                        resume.await()
                    }
                }
            val graph = LiveGraph(parts, listStore = { InMemoryLocalList() }, notificationStore = { _, _ -> store })
            val first = async { graph.session.signOut() }
            try {
                withTimeout(5_000) { reading.await() }
                assertEquals(Session.SignedOut, graph.session.observeSession().first())
                val fresh = graph.loader()
                try {
                    assertEquals(DataSource.NETWORK, fresh.load(CACHED).dataSource)
                    withTimeout(5_000) { graph.session.signOut() }
                    assertFalse(first.isCompleted)
                    assertEquals(DataSource.MEMORY_CACHE, fresh.load(CACHED).dataSource)
                } finally {
                    fresh.closeCache()
                }
            } finally {
                resume.complete(Unit)
                first.await()
            }
        }

    private companion object {
        val SIGN_IN = SignInId("0f8fad5b-d9cb-469f-a165-70867728950e")
        val PNG = "image/png".toMediaType()
        const val CACHED = "https://images.invalid/cached.png"
        const val HELD = "https://images.invalid/held.png"
    }
}

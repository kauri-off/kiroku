package io.github.kauri_off.kiroku.wiring

import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.imageDecoderEnabled
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageSignOutTest {
    @get:Rule
    val appEnds = AppEnds()

    private val context get() = RuntimeEnvironment.getApplication()
    private val work = SupervisorJob()
    private val scope = CoroutineScope(work + Dispatchers.Default)
    private val png by lazy {
        ByteArrayOutputStream()
            .also { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
    }

    private fun parts(
        source: String,
        beforeAnswer: (Interceptor.Chain) -> Unit = {},
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
                beforeAnswer(chain)
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "image/png")
                    .body(png.toResponseBody("image/png".toMediaType()))
                    .build()
            }
        return LiveParts(context, LiveSource(source, MalHosts.Real, standIn), "", Clock.systemUTC(), scope, { throw it }, device = device)
    }

    private fun graph(parts: LiveParts) = LiveGraph(parts, listStore = { InMemoryLocalList() })

    private fun LiveGraph.loader(): ImageLoader = newImageLoader(context).newBuilder().imageDecoderEnabled(false).build()

    private fun request(url: String) = ImageRequest.Builder(context).data(url).build()

    private suspend fun ImageLoader.load(url: String): SuccessResult = execute(request(url)) as SuccessResult

    @After
    fun endWork() = runBlocking { work.cancelAndJoin() }

    @Test
    fun `sign-out removes populated caches and prevents late requests writing to them`() =
        runBlocking {
            val atServer = CompletableDeferred<Unit>()
            val released = CountDownLatch(1)
            val cancelledAtServer = CompletableDeferred<Boolean>()
            val parts =
                parts("account") { chain ->
                    if (chain.request().url.toString() == HELD) {
                        atServer.complete(Unit)
                        check(released.await(15, TimeUnit.SECONDS))
                        cancelledAtServer.complete(chain.call().isCanceled())
                    }
                }
            parts.file("tokens").writeBytes(byteArrayOf(0) + SIGN_IN.encodeToByteArray())
            val graph = graph(parts)
            val loader = graph.loader()
            assertSame(graph.newImageLoader(context).memoryCache, loader.memoryCache)
            assertEquals(Session.Expired(SignInId(SIGN_IN)), graph.session.observeSession().first())
            val settings = NotificationSettings(on = false, alsoPlanned = true)
            graph.preferences.writeNotificationSettings(settings)

            val accountDisk = checkNotNull(loader.diskCache)
            val other = graph(parts("sandbox")).loader()
            val otherDisk = checkNotNull(other.diskCache)
            assertNotEquals(accountDisk.directory, otherDisk.directory)
            assertEquals(DataSource.NETWORK, other.load(CACHED).dataSource)
            assertEquals(DataSource.NETWORK, loader.load(CACHED).dataSource)
            assertEquals(DataSource.MEMORY_CACHE, loader.load(CACHED).dataSource)
            assertTrue(accountDisk.size > 0)

            val held = async { loader.load(HELD) }
            val sizing = CompletableDeferred<Unit>()
            val size = CompletableDeferred<Size>()
            val waitingForSize =
                request(SIZING)
                    .newBuilder()
                    .size {
                        sizing.complete(Unit)
                        size.await()
                    }.build()
            val late = async { loader.execute(waitingForSize) }
            try {
                withTimeout(5_000) {
                    atServer.await()
                    sizing.await()
                    graph.session.signOut()
                }
                assertEquals(Session.SignedOut, graph.session.observeSession().first())
                assertEquals(settings, graph.preferences.observeNotificationSettings().first())
                assertTrue(held.isCancelled)
                released.countDown()
                assertTrue(withTimeout(5_000) { cancelledAtServer.await() })
                size.complete(Size.ORIGINAL)
                late.join()
                assertTrue(late.isCancelled)

                assertTrue(checkNotNull(loader.memoryCache).keys.isEmpty())
                assertEquals(0L, accountDisk.size)
                assertNull(accountDisk.openSnapshot(CACHED))
                assertNull(accountDisk.openSnapshot(HELD))
                assertNull(accountDisk.openSnapshot(SIZING))
                assertTrue(otherDisk.size > 0)
                assertEquals(DataSource.MEMORY_CACHE, other.load(CACHED).dataSource)

                assertEquals(DataSource.NETWORK, loader.load(CACHED).dataSource)
                assertEquals(DataSource.MEMORY_CACHE, loader.load(CACHED).dataSource)
                assertEquals(DataSource.NETWORK, loader.load(HELD).dataSource)
            } finally {
                released.countDown()
                late.cancel()
                held.cancel()
                loader.shutdown()
                other.shutdown()
                accountDisk.shutdown()
                otherDisk.shutdown()
            }
        }

    @Test
    fun `a restarted graph reuses its disk cache until sign-out even when the loader is unused`() =
        runBlocking {
            val parts = parts("restarted")
            graph(parts).loader().apply {
                assertEquals(DataSource.NETWORK, load(CACHED).dataSource)
                diskCache?.shutdown()
                shutdown()
            }
            graph(parts).loader().apply {
                assertEquals(DataSource.DISK, load(CACHED).dataSource)
                diskCache?.shutdown()
                shutdown()
            }

            parts.file("tokens").writeBytes(byteArrayOf(0) + SIGN_IN.encodeToByteArray())
            val legacy = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coil3_disk_cache"
            FileSystem.SYSTEM.createDirectories(legacy)
            FileSystem.SYSTEM.write(legacy / "fixture") { write(png) }
            val graph = graph(parts)
            assertEquals(Session.Expired(SignInId(SIGN_IN)), graph.session.observeSession().first())
            graph.session.signOut()
            assertFalse(FileSystem.SYSTEM.exists(legacy))
            graph.newImageLoader(context).diskCache?.shutdown()

            val restarted = graph(parts).loader()
            try {
                assertEquals(0L, restarted.diskCache?.size)
                assertEquals(DataSource.NETWORK, restarted.load(CACHED).dataSource)
            } finally {
                restarted.diskCache?.shutdown()
                restarted.shutdown()
            }
        }

    private companion object {
        const val SIGN_IN = "0f8fad5b-d9cb-469f-a165-70867728950e"
        const val CACHED = "https://images.invalid/cached.png"
        const val HELD = "https://images.invalid/held.png"
        const val SIZING = "https://images.invalid/sizing.png"
    }
}

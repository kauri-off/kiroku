package io.github.kauri_off.kiroku.recorder

import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.repository.SignInStep
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.recorder.ReadCapture
import io.github.kauri_off.kiroku.data.recorder.Recording
import io.github.kauri_off.kiroku.data.session.TokensNotRead
import io.github.kauri_off.kiroku.wiring.KeysInMemory
import io.github.kauri_off.kiroku.wiring.LiveGraph
import io.github.kauri_off.kiroku.wiring.LiveParts
import io.github.kauri_off.kiroku.wiring.LiveSource
import io.github.kauri_off.kiroku.wiring.ServedMal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

// A title that a recording left on the list is dealt with when the app's graph is made: nothing has to ask for the recorder.
@RunWith(RobolectricTestRunner::class)
class RecorderStartTest {
    @get:Rule
    val mal = ServedMal()

    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob())
    private val device =
        object : Device {
            override fun isOnline() = true

            override fun observeOnline() = flowOf(true)

            override fun isMainThread() = false
        }
    private val unexpected = CopyOnWriteArrayList<Throwable>()

    private fun parts(source: LiveSource = LiveSource.Account) =
        LiveParts(context, source, "0123abcd", Clock.systemUTC(), scope, unexpected::add, device = device)

    // Tokens no key opens: the session reports them when it is first read, which only a request of the recorder's does here.
    private fun LiveParts.sealedTokens() = file("tokens").writeBytes(byteArrayOf(1) + SIGN_IN.encodeToByteArray() + ByteArray(40))

    private fun reportedWithin(millis: Long): Boolean =
        runBlocking { withTimeoutOrNull(millis) { while (unexpected.isEmpty()) delay(20) } != null }

    @After
    fun close() {
        scope.cancel()
        KeysInMemory.uninstall()
    }

    @Test
    fun `making the graph of a source with a title left on the list sets about removing it, with the recorder never asked for`() {
        val parts = parts()
        parts.sealedTokens()
        parts.file("recording").writeText("""{"id":5,"episodes":12,"name":"A finished title","user":"nobody"}""")

        LiveGraph(parts)

        assertTrue(reportedWithin(WAIT))
        assertTrue(unexpected.single() is TokensNotRead)
        assertTrue(parts.file("recording").exists())
    }

    @Test
    fun `without a title left, making the graph reads no session and asks nothing`() {
        val parts = parts()
        parts.sealedTokens()

        LiveGraph(parts)

        assertEquals(false, reportedWithin(QUIET))
    }

    @Test
    fun `complete and stopped captures use the app private no backup directory`() =
        runBlocking {
            KeysInMemory.install()
            val terminal = JSONObject(ServedMal.publicPages.first()).apply { getJSONObject("paging").remove("next") }.toString()
            val source =
                LiveSource(
                    "private-test",
                    mal.hosts,
                    standIn = { chain ->
                        val request = chain.request()
                        when (request.url.encodedPath) {
                            "/v2/anime/suggestions" ->
                                Response
                                    .Builder()
                                    .request(request)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body(terminal.toResponseBody())
                                    .build()
                            else -> chain.proceed(request)
                        }
                    },
                )
            val parts = parts(source)
            val graph = LiveGraph(parts)
            val signIn = graph.signIn
            var address: String? = null
            signIn.start {
                address = it
                true
            }
            val state = checkNotNull(address).toHttpUrl().queryParameter("state")
            signIn.redirected("${BuildConfig.OAUTH_REDIRECT}?code=the-code&state=$state")
            val step = withTimeout(WAIT) { signIn.step.first { it == SignInStep.SignedIn || it is SignInStep.Failed } }
            assertEquals(SignInStep.SignedIn, step)

            val recorder = graph.recorder
            recorder.recordReads(ReadCapture.Suggestions).join()
            val completed = recorder.state.value as Recording.ReadRecorded
            recorder.recordReads(ReadCapture.MangaList).join()
            val stopped = recorder.state.value as Recording.ReadStopped

            val root = parts.file("private-reads")
            assertEquals(context.noBackupFilesDir, root.parentFile)
            assertEquals(root, completed.folder.parentFile?.parentFile)
            val partial = checkNotNull(stopped.folder)
            assertEquals(root, partial.parentFile?.parentFile)
            assertTrue(completed.folder.resolve("suggestions_first.200.json").isFile)
            assertTrue(partial.resolve("manga_list_1.404.json").isFile)
            val shared = checkNotNull(context.getExternalFilesDir(null))
            assertFalse(shared.walkTopDown().any { it.name.startsWith("suggestions_first.") || it.name.startsWith("manga_list_1.") })
        }

    private companion object {
        const val WAIT = 15_000L
        const val QUIET = 1_500L
        const val SIGN_IN = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}

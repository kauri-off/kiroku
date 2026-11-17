package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.rules.appSources
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.Clock

// The score distributions of a live graph: one client per source and process, its refusal in the source's own file.
@RunWith(RobolectricTestRunner::class)
class LiveScoresTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val server = MockWebServer()

    private val device =
        object : Device {
            override fun isOnline() = true

            override fun observeOnline() = flowOf(true)

            override fun isMainThread() = false
        }

    @After
    fun close() = server.close()

    @Test
    fun `the client is the source's one, and a refusal is kept in the source's own file, which is not backed up`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .setHeader("Retry-After", "60")
                    .build(),
            )
            server.start()
            val source = LiveSource("refused", MalHosts.Real, tenrai = server.url("/v1/").toString())
            val parts = LiveParts(context, source, "0123abcd", Clock.systemUTC(), backgroundScope, {}, device = device, clockSet = flowOf())
            val graph = LiveGraph(parts)

            assertSame(parts.scores, parts.scores)
            assertSame(parts.scores, graph.scores)
            assertEquals(Outcome.Failure(DataError.Refused), graph.scores.distribution(AnimeId(1)))
            assertEquals("/v1/anime/1/statistics", server.takeRequest().url.encodedPath)
            assertTrue(File(context.noBackupFilesDir, "refused.tenrai.block").isFile)
            assertFalse(File(context.noBackupFilesDir, "account.tenrai.block").exists())
        }

    // So that Tenrai's spacing and its refusal hold for every request of the process.
    @Test
    fun `the score distribution client is made in the live graph's file and nowhere else`() {
        val making = Regex("""\bTenraiScores\s*\(""")
        assertEquals(listOf("LiveGraph.kt"), appSources().filter { making.containsMatchIn(it.readText()) }.map { it.name })
    }
}

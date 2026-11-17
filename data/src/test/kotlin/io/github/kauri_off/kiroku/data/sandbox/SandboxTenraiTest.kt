package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.recorder.TenraiRequests
import io.github.kauri_off.kiroku.data.tenrai.TenraiScores
import io.github.kauri_off.kiroku.data.tenrai.tenraiDistribution
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// The sandbox answers the app's Tenrai client on the device, from the recorded answers, and reaches no host.
class SandboxTenraiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sandbox by lazy {
        Sandbox(
            read = { recorded("$it.200.json") },
            readTenrai = ::recordedTenrai,
            poster = ::poster,
            clock = TestClockAt,
            file = { File(folder.root, it) },
        )
    }
    private val client by lazy { SandboxClient(sandbox.mal) }

    private fun scores() = TenraiScores(client.http, client.clock, File(folder.root, "tenrai.block"))

    @Test
    fun `every recorded title is answered with its recording, and every title the sandbox knows is a title of its own`() {
        val recorded = TenraiRequests.filter { it.name != "unknown" }

        assertEquals(recorded.associate { it.id to "${it.name}.200.json" }, SandboxTenrai.Recorded)
        assertEquals("${TenraiRequests.single { it.name == "unknown" }.name}.404.json", SandboxTenrai.NO_RECORD)
        for (id in SandboxTenrai.Recorded.keys) assertTrue("$id", id in sandboxTitles)
    }

    @Test
    fun `the app's client reads each recorded title's distribution, any other title as not known, and offline as no connection`() =
        runTest {
            val scores = scores()
            for ((id, file) in SandboxTenrai.Recorded) {
                assertEquals(file, Outcome.Success(tenraiDistribution(recordedTenrai(file)) {}), scores.distribution(AnimeId(id)))
            }
            assertEquals(Outcome.Success(null), scores.distribution(AnimeId(21)))
            sandbox.mal.offline = true
            assertEquals(Outcome.Failure(DataError.NoConnection), scores.distribution(AnimeId(1)))

            assertTrue(sandbox.mal.asked.all { it.startsWith("GET api.tenrai.org/v1/anime/") })
            assertEquals(emptyList<Throwable>(), client.unexpected)
        }

    private companion object {
        val TestClockAt =
            io.github.kauri_off.kiroku.data.network
                .TestClock()
    }
}

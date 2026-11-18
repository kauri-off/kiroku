package io.github.kauri_off.kiroku.notify

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.wiring.ListWork
import io.github.kauri_off.kiroku.wiring.WithoutScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

// One periodic run over an app without a screen whose steps the test decides.
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodicRunTest {
    private val steps = mutableListOf<String>()

    private inner class App(
        val list: suspend () -> ListWork<Outcome<Unit>> = { ListWork.Done(Outcome.Success(Unit)) },
        val airing: suspend () -> Outcome<Unit> = { Outcome.Success(Unit) },
    ) : WithoutScreen {
        val runs = RecordedRuns()

        override val notificationRuns: NotificationRuns =
            object : NotificationRuns {
                override suspend fun run(trigger: RunTrigger): RunReport {
                    steps += "engine"
                    return runs.run(trigger)
                }
            }

        override suspend fun syncList(): ListWork<Outcome<Unit>> {
            steps += "list"
            return list()
        }

        override suspend fun sendToList(write: ListWrite): ListWork<Outcome<Unit>> = error("A periodic run writes nothing")

        override suspend fun updateAiring(): Outcome<Unit> {
            steps += "airing"
            return airing()
        }

        override suspend fun titleOnList(id: AnimeId): Anime? = error("A periodic run marks nothing")

        override suspend fun session(): Session = error("A periodic run reads no session of its own")

        override val notSaved: NotSavedNotice? = null
    }

    @Test
    fun `the list is downloaded and the airing data updated before the engine runs, so it reads them as they now are`() =
        runTest {
            val report = periodicRun(App())

            assertEquals(listOf("list", "airing", "engine"), steps)
            assertEquals(ListWork.Done(Outcome.Success(Unit)), report.list)
            assertEquals(Outcome.Success(Unit), report.airing)
            assertEquals(RunTrigger.Periodic, report.notifications!!.trigger)
        }

    @Test
    fun `a step that throws keeps neither the next step nor the engine from its run, and the run then fails with it`() =
        runTest {
            val thrown = IllegalStateException("The session's store cannot be read")
            for (app in listOf(App(list = { throw thrown }), App(airing = { throw thrown }))) {
                steps.clear()

                val failure = runCatching { periodicRun(app) }.exceptionOrNull()

                // Coroutines may hand back a copy of what was thrown, with the caller's frames.
                assertEquals(thrown.message, failure?.message)
                assertTrue("$failure", failure is IllegalStateException)
                assertEquals(listOf("list", "airing", "engine"), steps)
                assertEquals(listOf(RunTrigger.Periodic), app.runs.ended.toList())
            }
        }

    // The system stops a run at ten minutes; the engine's run must come before that, however slow MAL and AniList are.
    @Test
    fun `steps that never answer are left after their waits, and the engine runs inside the system's ten minutes`() =
        runTest {
            val app = App(list = { awaitCancellation() }, airing = { awaitCancellation() })

            val report = periodicRun(app)

            assertEquals(null, report.list)
            assertEquals(null, report.airing)
            assertEquals(listOf(RunTrigger.Periodic), app.runs.ended.toList())
            assertTrue("${currentTime.milliseconds}", currentTime.milliseconds <= 9.minutes)
        }
}

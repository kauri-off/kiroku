package io.github.kauri_off.kiroku.shell

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ActivityScenario
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.Untouched
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// ARCHITECTURE 6.2: a message belongs to the shell, so it outlives the screen that posted it and a recreation.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ShellMessagesTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private var reads = 0
    private var failedReads = 0

    // The list as the shell reads it, counting each read of a title and each that ended because the store failed.
    private val counted =
        object : ListRepository by graph.list {
            override fun observeEntry(id: AnimeId) =
                graph.list
                    .observeEntry(id)
                    .onStart { reads++ }
                    .onCompletion { if (it is DataException) failedReads++ }
        }
    private val messages = ShellMessages(scope, counted)
    private var composed by mutableStateOf(true)

    // The shell's host, which a recreation makes anew.
    private fun shell() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                if (composed) {
                    val host = remember { SnackbarHostState() }
                    ShownMessages(messages, host)
                    SnackbarHost(host)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun recreate() {
        composed = false
        compose.waitForIdle()
        composed = true
        compose.waitForIdle()
    }

    private val anime = FixtureAnime.exactToWatch

    private fun watched() =
        runBlocking {
            graph.list
                .observeEntry(anime.id)
                .first()
                ?.values
                ?.watchedEpisodes
        }

    @Test
    fun `a message cut short by a recreation shows again, and its Undo still writes`() {
        shell()
        val before = runBlocking { graph.list.observeEntry(anime.id).first()!! }
        val change = runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }!!
        compose.runOnUiThread { messages.post(ShellMessage(ShellMessage.Text.EpisodeWatched(13), ShellMessage.Action.Undo(change.undo!!))) }
        compose.onNodeWithText("Episode\u00A013 watched").assertExists()
        recreate()
        compose.onNodeWithText("Episode\u00A013 watched").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(before.values.watchedEpisodes, watched())
        assertNull(messages.current.value)
    }

    @Test
    fun `a newer message replaces the one shown`() {
        shell()
        compose.runOnUiThread { messages.post(ShellMessage(ShellMessage.Text.EpisodeWatched(13))) }
        compose.runOnUiThread { messages.post(ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection))) }
        compose.waitForIdle()
        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("Episode\u00A013 watched").assertDoesNotExist()
    }

    // W2: the failure is heard by the shell's view model, which lives through a recreation.
    @Test
    fun `a write failure that arrives while no screen is composed is shown afterwards`() {
        val shell = ShellViewModel(graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                if (composed) {
                    val host = remember { SnackbarHostState() }
                    ShownMessages(shell.messages, host)
                    SnackbarHost(host)
                }
            }
        }
        compose.waitForIdle()
        composed = false
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = true
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        composed = true
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }

    private fun shellIn(activity: ActivityScenario<ComponentActivity>): ShellViewModel {
        lateinit var shell: ShellViewModel
        activity.onActivity {
            shell = ViewModelProvider(it, viewModelFactory { initializer { ShellViewModel(graph.list) } })[ShellViewModel::class.java]
        }
        runMainLooper()
        return shell
    }

    // W2: the shell's view model lives as long as the activity, so a failure made while there is none waits for the next.
    @Test
    fun `a write failure made while the activity is gone is shown once by the next one`() {
        ActivityScenario.launch(ComponentActivity::class.java).use { shellIn(it) }
        graph.demo.server.failingWrites.value = true
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        runMainLooper()

        val shown = ActivityScenario.launch(ComponentActivity::class.java).use { shellIn(it).messages.current.value }
        assertEquals(ShellMessage.Text.CouldNotSave, shown?.message?.text)
        assertTrue(shown?.message?.action is ShellMessage.Action.Retry)
        assertNull(ActivityScenario.launch(ComponentActivity::class.java).use { shellIn(it).messages.current.value })
    }

    // W2: once a download shows MAL holds what the failed save's Retry would set, there is nothing left to say.
    @Test
    fun `a write failure made while the activity is gone is not shown once a download shows MAL holds its values`() {
        ActivityScenario.launch(ComponentActivity::class.java).use { shellIn(it) }
        graph.demo.server.failingWrites.value = true
        val before = checkNotNull(watched())
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        runMainLooper()
        graph.demo.server.failingWrites.value = false
        graph.demo.server.editOnWebsite(
            anime,
            runBlocking { graph.list.observeEntry(anime.id).first()!! }.values.copy(
                watchedEpisodes =
                    before + 1,
            ),
        )
        val sync = scope.async { graph.list.sync() }
        runMainLooper()
        assertTrue(sync.isCompleted)
        assertEquals(Outcome.Success(Unit), runBlocking { sync.await() })
        assertEquals(before + 1, watched())

        assertNull(ActivityScenario.launch(ComponentActivity::class.java).use { shellIn(it).messages.current.value })
    }

    private val moved = ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(ListWrite.Remove(anime)))

    private fun watched(episode: Int) =
        ShellMessage(ShellMessage.Text.EpisodeWatched(episode), ShellMessage.Action.Undo(ListWrite.Remove(anime)))

    private fun failed(episode: Int) =
        ShellMessage(
            ShellMessage.Text.CouldNotSave,
            ShellMessage.Action.Retry(WriteFailure(DataError.ServerError, ListWrite.SetWatched(anime, episode), list = null).retry),
        )

    // A failed save of the count, which MAL last confirmed at 12; its Retry is judged against the title.
    private fun failedSave(episode: Int): ShellMessage {
        val confirmed = runBlocking { graph.list.observeEntry(anime.id).first()!! }.values
        val failure =
            WriteFailure(
                DataError.ServerError,
                ListWrite.Save(anime, confirmed.copy(watchedEpisodes = episode), setOf(ListField.WatchedEpisodes)),
                list = (runBlocking { graph.session.observeSession().first() } as Session.SignedIn).signIn,
                untouched = Untouched(setOf(confirmed)),
            )
        return ShellMessage(ShellMessage.Text.CouldNotSave, ShellMessage.Action.Retry(failure.retry))
    }

    // Posts [posted] in order, then lets each shown message time out; returns what was shown, in order.
    private fun shownAfter(vararg posted: ShellMessage): List<ShellMessage> {
        posted.forEach(messages::post)
        return generateSequence {
            messages.current.value
                ?.also { messages.ended(it, actionPerformed = false) }
                ?.message
        }.toList()
    }

    @Test
    fun `ordinary messages replace each other, so quick taps on several rows build no queue`() {
        assertEquals(listOf(watched(3)), shownAfter(watched(1), watched(2), watched(3)))
    }

    // An ordinary message's row already shows what happened; held back, its Undo would come long after its tap.
    @Test
    fun `the Undo of a title that left its tab is not replaced, and an ordinary message meanwhile is dropped`() {
        assertEquals(listOf(moved), shownAfter(moved, watched(2), watched(3)))
        assertEquals(listOf(moved), shownAfter(watched(1), moved))
    }

    // A sign-out leaves no message of the user who signed out, shown, waiting or asking for an edit sheet.
    @Test
    fun `clearing ends the message shown and drops the waiting ones and the edit request`() {
        compose.runOnUiThread {
            messages.post(ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(anime.id)))
            messages.ended(messages.current.value!!, actionPerformed = true)
            listOf(failed(2), moved).forEach(messages::post)
        }
        assertEquals(anime.id, messages.editRequest.value)
        assertEquals(failed(2), messages.current.value?.message)
        compose.runOnUiThread { messages.clear() }
        assertNull(messages.current.value)
        assertNull(messages.editRequest.value)
        compose.runOnUiThread { messages.post(watched(3)) }
        assertEquals(listOf(watched(3)), shownInTurn())
    }

    @Test
    fun `two messages that must stay are shown one after the other`() {
        assertEquals(listOf(failed(1), moved, failed(2)), shownAfter(failed(1), watched(3), moved, failed(2), watched(4)))
    }

    // B3's m1: a stale Undo would take back a step the user had already taken back.
    @Test
    fun `a waiting Undo is dropped once its title has been written, and one whose title has not is shown`() {
        val other = FixtureAnime.oneShortOfTotal
        val otherMoved = ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(ListWrite.Remove(other)))
        compose.runOnUiThread { listOf(moved, otherMoved, moved).forEach(messages::post) }
        runMainLooper()
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        runMainLooper()
        assertEquals(listOf(moved, otherMoved), shownInTurn())
    }

    // W2, 7.2: a waiting Retry goes by the rule of a shown one, once nothing is left to set, and not before.
    @Test
    fun `a waiting Retry stays while its title changes and it still has something to set, and goes once nothing is left`() {
        compose.runOnUiThread { listOf(moved, failedSave(15)).forEach(messages::post) }
        runMainLooper()
        runBlocking {
            graph.list.write(
                ListWrite.Save(
                    anime,
                    graph.demo.server
                        .entry(anime.id)!!
                        .values
                        .copy(score = 3),
                    setOf(ListField.Score),
                ),
            )
        }
        runMainLooper()
        val kept = failedSave(15)
        assertEquals(listOf(moved, kept).map { it.text }, shownInTurn().map { it.text })

        compose.runOnUiThread { listOf(moved, failedSave(15)).forEach(messages::post) }
        runMainLooper()
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        runMainLooper()
        assertEquals(listOf(moved), shownInTurn())
    }

    // Lets each shown message time out; returns what was shown, in order.
    private fun shownInTurn(): List<ShellMessage> =
        compose.runOnUiThread<List<ShellMessage>> {
            generateSequence {
                messages.current.value
                    ?.also { messages.ended(it, actionPerformed = false) }
                    ?.message
            }.toList()
        }

    // Runs the main looper alone: with nothing composed, compose.waitForIdle waits two real seconds for a root.
    private fun runMainLooper() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(duration: Duration) = shadowOf(Looper.getMainLooper()).idleFor(duration.toJavaDuration())

    // Makes the store unreadable and runs the clock until a watch's read has failed, whatever the list's own attempts take.
    private fun storeFailsUntilAReadEnds() {
        val failed = failedReads
        graph.demo.store.unreadable.value = true
        repeat(600) { if (failedReads == failed) idleFor(100.milliseconds) }
        assertTrue("no read failed", failedReads > failed)
    }

    // The store failed and came back; the title written since is compared with what was read before the failure.
    @Test
    fun `a waiting Undo is watched again when the list is read again after a failure`() {
        compose.runOnUiThread { listOf(failed(1), moved).forEach(messages::post) }
        runMainLooper()
        storeFailsUntilAReadEnds()
        graph.demo.store.unreadable.value = false
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        idleFor(ShellMessages.REREAD)
        assertEquals(listOf(failed(1)), shownInTurn())
    }

    private val other = FixtureAnime.oneShortOfTotal

    private val otherFailed =
        ShellMessage(
            ShellMessage.Text.CouldNotSave,
            ShellMessage.Action.Retry(WriteFailure(DataError.ServerError, ListWrite.SetWatched(other, 1), list = null).retry),
        )

    private fun undoOf(change: ListChange) = ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(change.undo!!))

    // Posts [waiting] behind a shown Retry while the store cannot be read, and brings the store back after its first failure.
    private fun waitingThroughFailure(
        waiting: ShellMessage,
        meanwhile: () -> Unit = {},
    ) {
        compose.runOnUiThread { listOf(otherFailed, waiting).forEach(messages::post) }
        storeFailsUntilAReadEnds()
        graph.demo.store.unreadable.value = false
        meanwhile()
        idleFor(ShellMessages.REREAD * 2)
    }

    // A writer other than the shell, as a sync, changes the title between the store's return and the watch's next read.
    @Test
    fun `a waiting Undo compares with the values its change left, not with the first read after the store returns`() {
        val change = runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }!!
        runMainLooper()
        waitingThroughFailure(undoOf(change)) { runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) } }
        assertEquals(listOf(otherFailed), shownInTurn())
    }

    @Test
    fun `a waiting Undo whose title was not written stays through a store that failed and came back`() {
        val change = runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }!!
        runMainLooper()
        waitingThroughFailure(undoOf(change))
        assertEquals(listOf(otherFailed, undoOf(change)), shownInTurn())
    }

    // A failure carries what it found untouched, so a Retry read first after the store returns is judged all the same.
    @Test
    fun `a waiting Retry posted while the list cannot be read is judged once it can be, and keeps its Retry while something is left`() {
        val retry = failedSave(15)
        waitingThroughFailure(retry)
        assertEquals(listOf(otherFailed, retry), shownInTurn())
        waitingThroughFailure(failedSave(15)) {
            runBlocking {
                graph.list.write(ListWrite.MarkNextWatched(anime))
                graph.list.write(ListWrite.MarkNextWatched(anime))
            }
        }
        assertEquals(listOf(otherFailed), shownInTurn())
    }

    // Each failed read of the store is logged by the list, so a store that stays broken is read seldom, yet still read.
    @Test
    fun `a store that stays broken is read again no sooner than REREAD, then less and less often, and at least every few minutes`() {
        compose.runOnUiThread { listOf(otherFailed, moved).forEach(messages::post) }
        runMainLooper()
        storeFailsUntilAReadEnds()
        val failed = reads
        idleFor(ShellMessages.REREAD - 1.seconds)
        assertEquals(failed, reads)
        idleFor(1.seconds)
        assertEquals(failed + 1, reads)
        idleFor(2.hours)
        assertTrue("$reads reads", reads in 20..40)
    }

    // The action's write changes the count the waiting one would set, so it has nothing left to set.
    @Test
    fun `tapping a Retry drops a waiting one of the same title that it leaves nothing to set`() {
        compose.runOnUiThread { listOf(failedSave(13), failedSave(15)).forEach(messages::post) }
        runMainLooper()
        compose.runOnUiThread { messages.ended(messages.current.value!!, actionPerformed = true) }
        runMainLooper()
        assertEquals(13, watched())
        runMainLooper()
        assertNull(messages.current.value)
    }

    @Test
    fun `a write the shell makes drops a waiting message of that title at once`() {
        val shown =
            compose.runOnUiThread<List<ShellMessage>> {
                listOf(otherFailed, moved).forEach(messages::post)
                messages.write(ListWrite.MarkNextWatched(anime))
                generateSequence {
                    messages.current.value
                        ?.also { messages.ended(it, actionPerformed = false) }
                        ?.message
                }.toList()
            }
        assertEquals(listOf(otherFailed), shown)
    }

    // A sync that brings the same values with MAL's newer time stamp leaves nothing the Undo would act on changed.
    @Test
    fun `a waiting Undo stays when only its title's time stamp changes`() {
        compose.runOnUiThread { listOf(failed(1), moved).forEach(messages::post) }
        runMainLooper()
        val before = graph.demo.server.entry(anime.id)!!
        graph.demo.server.editOnWebsite(before.anime, before.values)
        scope.launch { graph.list.sync() }
        runMainLooper()
        val after = runBlocking { graph.list.observeEntry(anime.id).first() }!!
        assertNotEquals(before.updatedAt, after.updatedAt)
        assertEquals(before.values, after.values)
        assertEquals(listOf(failed(1), moved), shownInTurn())
    }

    @Test
    fun `an Undo that adds nothing back, as an episode watched, does not have to stay`() {
        assertEquals(false, watched(1).mustStay)
        assertEquals(false, ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(anime.id)).mustStay)
        assertEquals(false, ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection)).mustStay)
    }

    // A save that fails for the session cannot succeed by a Retry; the session banner offers sign-in instead.
    @Test
    fun `a save that fails for the expired session offers no Retry`() {
        val shell = ShellViewModel(graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                ShownMessages(shell.messages, host)
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        graph.demo.server.sessionExpired.value = true
        compose.waitForIdle()
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        assertNull(
            shell.messages.current.value
                ?.message
                ?.action,
        )
    }

    // An Undo of a list that has ended is refused for good: a Retry of it could only be refused again.
    @Test
    fun `an Undo refused because its list has ended offers no Retry`() {
        val shell = ShellViewModel(graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                ShownMessages(shell.messages, host)
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        val change = runBlocking { checkNotNull(graph.list.write(ListWrite.MarkNextWatched(anime))) }
        runBlocking { graph.demo.session.signOut() }
        compose.waitForIdle()
        runBlocking { graph.list.write(change.undo!!) }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        assertNull(
            shell.messages.current.value
                ?.message
                ?.action,
        )
    }

    // W2: a later "+1" on another title does not take the failed save's Retry away.
    @Test
    fun `a failed save keeps its Retry through a newer message, and Retry writes`() {
        val shell = ShellViewModel(graph.list)
        val other = FixtureAnime.oneShortOfTotal
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                ShownMessages(shell.messages, host)
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        val before = watched()
        graph.demo.server.failingWrites.value = true
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = false
        compose.onNodeWithText("Could not save").assertExists()
        assertEquals(before, watched())
        val change = runBlocking { graph.list.write(ListWrite.StepWatched(other, -1)) }!!
        compose.runOnUiThread {
            shell.messages.post(ShellMessage(ShellMessage.Text.EpisodeWatched(10), ShellMessage.Action.Undo(change.undo!!)))
        }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(before!! + 1, watched())
        compose.onNodeWithText("Episode\u00A010 watched").assertDoesNotExist()
    }

    // A failed save that MAL made after all: once a download shows the title as the Retry would write it, the message goes.
    @Test
    fun `a failed save's message ends once its title holds what its Retry writes, a website edit beside, and stays while it does not`() {
        val shell = ShellViewModel(graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                ShownMessages(shell.messages, host)
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = true
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = false
        val retry =
            (
                shell.messages.current.value!!
                    .message.action as ShellMessage.Action.Retry
            ).write as ListWrite.Retry
        val values = (retry.failure.write as ListWrite.Save).values

        // The engine runs on the main thread here, so a sync is launched there rather than waited for.
        compose.runOnUiThread { scope.launch { graph.list.sync() } }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()

        graph.demo.server.editOnWebsite(anime, values.copy(score = if (values.score == 3) 4 else 3))
        compose.runOnUiThread { scope.launch { graph.list.sync() } }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertDoesNotExist()
        assertEquals(null, shell.messages.current.value)
    }

    // A "+1" then a "−1" whose first request fails: the Retry holds the values the title rolls back to, and MAL may
    // hold the one in between, so the message stays until a change says more.
    @Test
    fun `a failed save whose Retry would write the values the title rolled back to keeps its message`() {
        val shell = ShellViewModel(graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                ShownMessages(shell.messages, host)
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        val before = watched()
        graph.demo.server.failingWrites.value = true
        runBlocking {
            graph.list.write(ListWrite.MarkNextWatched(anime))
            graph.list.write(ListWrite.StepWatched(anime, -1))
        }
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = false
        val retry =
            (
                shell.messages.current.value!!
                    .message.action as ShellMessage.Action.Retry
            ).write as ListWrite.Retry
        assertEquals(before, (retry.failure.write as ListWrite.Save).values.watchedEpisodes)
        assertEquals(before, watched())

        compose.runOnUiThread { scope.launch { graph.list.sync() } }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
    }

    private fun shellShown(): ShellViewModel {
        val shell = ShellViewModel(graph.list)
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                val host = remember { SnackbarHostState() }
                ShownMessages(shell.messages, host)
                SnackbarHost(host)
            }
        }
        compose.waitForIdle()
        return shell
    }

    // "Mark watched" from a notification: its write waits for MAL's answer, so it is started on the engine's thread.
    private fun markWatchedInBackground(episode: Int) {
        lateinit var sent: Deferred<Outcome<Unit>>
        compose.runOnUiThread { sent = scope.async { graph.list.send(ListWrite.MarkWatched(anime, episode)) } }
        compose.waitForIdle()
        assertEquals(Outcome.Success(Unit), runBlocking { sent.await() })
    }

    // W2: a Retry sets only what MAL has not changed since the failure, so the episode the notification marked stays.
    @Test
    fun `a failed save's Retry tapped after a send changed the count sets the score it carries and leaves the send's count`() {
        shellShown()
        val opened = runBlocking { graph.list.observeEntry(anime.id).first()!! }.values
        graph.demo.server.failingWrites.value = true
        val edited = opened.copy(watchedEpisodes = 15, score = 3)
        runBlocking { graph.list.write(ListWrite.Save(anime, edited, setOf(ListField.WatchedEpisodes, ListField.Score))) }
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = false
        compose.onNodeWithText("Could not save").assertExists()

        markWatchedInBackground(13)
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        val expected = opened.copy(watchedEpisodes = 13, score = 3)
        assertEquals(
            expected,
            graph.demo.server
                .entry(anime.id)
                ?.values,
        )
        assertEquals(expected, runBlocking { graph.list.observeEntry(anime.id).first()!! }.values)
    }

    // A download that changed each value the Retry sets leaves it nothing: the message goes, and a tap as it went writes nothing.
    @Test
    fun `a failed save's message goes once a download changed the count it would set, and its Retry leaves that count`() {
        val shell = shellShown()
        graph.demo.server.failingWrites.value = true
        runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = false
        val posted = checkNotNull(shell.messages.current.value)
        val requests = graph.demo.server.requests.size

        graph.demo.server.editOnWebsite(
            anime,
            runBlocking { graph.list.observeEntry(anime.id).first()!! }.values.copy(watchedEpisodes = 20),
        )
        compose.runOnUiThread { scope.launch { graph.list.sync() } }
        compose.waitForIdle()
        assertEquals(20, watched())
        compose.onNodeWithText("Could not save").assertDoesNotExist()

        compose.runOnUiThread { shell.messages.ended(posted, actionPerformed = true) }
        compose.waitForIdle()
        assertEquals(20, watched())
        assertEquals(requests, graph.demo.server.requests.size)
    }

    // W3: an Undo takes back its own step from the count as it is, so the episode a send marked since stays.
    @Test
    fun `an Undo of a +1 tapped after a send takes back its own step alone`() {
        shell()
        val change = runBlocking { graph.list.write(ListWrite.MarkNextWatched(anime)) }!!
        compose.runOnUiThread { messages.post(ShellMessage(ShellMessage.Text.EpisodeWatched(13), ShellMessage.Action.Undo(change.undo!!))) }
        compose.waitForIdle()

        markWatchedInBackground(14)
        assertEquals(14, watched())
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(13, watched())
        assertEquals(
            13,
            graph.demo.server
                .entry(anime.id)
                ?.values
                ?.watchedEpisodes,
        )
    }

    // A removal is retried only while the entry is as it was, so a send that marks an episode leaves it nothing to do.
    @Test
    fun `a failed removal's message goes once a send marked an episode, and its Retry removes nothing`() {
        val shell = shellShown()
        graph.demo.server.failingWrites.value = true
        runBlocking { graph.list.write(ListWrite.Remove(anime)) }
        compose.waitForIdle()
        graph.demo.server.failingWrites.value = false
        compose.onNodeWithText("Could not save").assertExists()
        val posted = checkNotNull(shell.messages.current.value)

        markWatchedInBackground(13)
        compose.onNodeWithText("Could not save").assertDoesNotExist()
        compose.runOnUiThread { shell.messages.ended(posted, actionPerformed = true) }
        compose.waitForIdle()
        assertEquals(13, watched())
        assertEquals(
            13,
            graph.demo.server
                .entry(anime.id)
                ?.values
                ?.watchedEpisodes,
        )
    }

    // A message never offers a Retry that would do nothing, also when that was so before it showed.
    @Test
    fun `a failure whose Retry has nothing left to set is not shown`() {
        val shell = shellShown()
        val values = runBlocking { graph.list.observeEntry(anime.id).first()!! }.values
        val failure =
            WriteFailure(
                DataError.ServerError,
                ListWrite.Save(anime, values.copy(watchedEpisodes = 20), setOf(ListField.WatchedEpisodes)),
                list = null,
                untouched = Untouched(setOf(values.copy(watchedEpisodes = 5))),
            )
        compose.runOnUiThread {
            shell.messages.post(
                ShellMessage(ShellMessage.Text.CouldNotSave, ShellMessage.Action.Retry(failure.retry)),
            )
        }
        compose.waitForIdle()
        assertNull(shell.messages.current.value)
        compose.onNodeWithText("Could not save").assertDoesNotExist()
    }

    // 7.2 and W2: a failed date waiting behind another message survives a "+1", which leaves the status and the date alone.
    @Test
    fun `a failed start date waiting behind another message is still offered after a +1, and its Retry sets the date`() {
        val shell = shellShown()
        val other = FixtureAnime.oneShortOfTotal
        compose.runOnUiThread {
            shell.messages.post(ShellMessage(ShellMessage.Text.MovedToCompleted, ShellMessage.Action.Undo(ListWrite.Remove(other))))
        }
        val opened = runBlocking { graph.list.observeEntry(anime.id).first()!! }.values
        val dated = opened.copy(startDate = PartialDate.Day(LocalDate.of(2025, 10, 5)))
        graph.demo.server.offline.value = true
        runBlocking { graph.list.write(ListWrite.Save(anime, dated, setOf(ListField.StartDate))) }
        compose.waitForIdle()
        graph.demo.server.offline.value = false
        assertEquals(opened, runBlocking { graph.list.observeEntry(anime.id).first()!! }.values)

        compose.runOnUiThread { shell.messages.write(ListWrite.MarkNextWatched(anime)) }
        compose.waitForIdle()
        assertEquals(13, watched())
        compose.runOnUiThread { shell.messages.ended(shell.messages.current.value!!, actionPerformed = false) }
        compose.waitForIdle()
        compose.onNodeWithText("Could not save").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(
            dated.copy(watchedEpisodes = 13),
            graph.demo.server
                .entry(anime.id)
                ?.values,
        )
    }
}

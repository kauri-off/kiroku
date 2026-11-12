package io.github.kauri_off.kiroku.title

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TitleViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private class Subject(
        val graph: TestGraph,
        val messages: ShellMessages,
        val title: TitleViewModel,
        private val test: TestScope,
    ) {
        var screen: Job = test.showing(title.state)

        // What the page shows once the events so far have been handled.
        val state: TitleState get() = test.runCurrent().let { title.state.value }
        val message: ShellMessage? get() = messages.current.value?.message

        fun tapAction() = messages.ended(messages.current.value!!, actionPerformed = true)

        val shown: TitleState.Shown get() = state as TitleState.Shown

        fun watched() = shown.page.entry?.watchedEpisodes

        suspend fun values() =
            graph.list
                .observeEntry(shown.page.anime.id)
                .first()
                ?.values
    }

    private fun test(
        id: AnimeId = FixtureAnime.exactToWatch.id,
        saved: SavedStateHandle = SavedStateHandle(),
        before: TestGraph.() -> Unit = {},
        settle: Boolean = true,
        lateObservers: Boolean = false,
        slowWrites: Boolean = false,
        body: suspend TestScope.(Subject) -> Unit,
    ) = runTest(dispatcher) {
        val scope = foregroundScope()
        val graph = TestGraph(Demo(Fixtures.reference, scope), lateObservers, slowWrites).apply(before)
        val messages = ShellMessages(scope, graph.list)
        val subject = Subject(graph, messages, title(graph, id, saved, messages), this)
        if (settle) advanceUntilIdle()
        body(subject)
        scope.cancel()
    }

    @Test
    fun `the page shows the stored title at once and the details when they arrive`() =
        test(settle = false) { s ->
            runCurrent()
            assertEquals(FixtureAnime.exactToWatch, s.shown.page.anime)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)
            assertEquals(
                12,
                s.shown.page.entry
                    ?.watchedEpisodes,
            )
            assertTrue(
                s.shown.page.related
                    .isNotEmpty(),
            )
        }

    @Test
    fun `SetWatched writes the count at once and shows no snackbar below the total`() =
        test { s ->
            s.title.onEvent(TitleEvent.SetWatched(13))
            advanceUntilIdle()
            assertEquals(13, s.values()?.watchedEpisodes)
            assertEquals(
                13,
                s.shown.page.entry
                    ?.watchedEpisodes,
            )
            assertNull(s.message)
        }

    // Three quick taps on the stepper's plus before the page shows the first: each counts (W3, section 7.3).
    private fun stepperBurst(lateObservers: Boolean) =
        test(lateObservers = lateObservers) { s ->
            repeat(3) { s.title.onEvent(TitleEvent.StepWatched(1)) }
            advanceUntilIdle()
            assertEquals(15, s.values()?.watchedEpisodes)
            s.title.onEvent(TitleEvent.StepWatched(-1))
            s.title.onEvent(TitleEvent.StepWatched(-1))
            advanceUntilIdle()
            assertEquals(13, s.values()?.watchedEpisodes)
        }

    @Test
    fun `three quick stepper taps all count`() = stepperBurst(lateObservers = false)

    @Test
    fun `three quick stepper taps all count, with late observers`() = stepperBurst(lateObservers = true)

    @Test
    fun `W3 stepping up to the total completes the title and Undo takes back only the completing step`() =
        test(lateObservers = true) { s ->
            s.title.onEvent(TitleEvent.SetWatched(22))
            advanceUntilIdle()
            val before = s.values()
            s.title.onEvent(TitleEvent.StepWatched(1))
            s.title.onEvent(TitleEvent.StepWatched(1))
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, s.values()?.status)
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
            s.tapAction()
            advanceUntilIdle()
            assertEquals(before?.copy(watchedEpisodes = 23), s.values())
        }

    @Test
    fun `a typed count keeps a score set since the page last showed the entry`() =
        test(lateObservers = true) { s ->
            val anime = FixtureAnime.exactToWatch
            s.graph.list.write(ListWrite.Save(anime, s.values()!!.copy(score = 3)))
            s.title.onEvent(TitleEvent.SetWatched(14))
            advanceUntilIdle()
            assertEquals(3, s.values()?.score)
            assertEquals(14, s.values()?.watchedEpisodes)
        }

    @Test
    fun `SetWatched to the total completes the title, and the snackbar's Undo restores every value`() =
        test { s ->
            val before = s.values()
            s.title.onEvent(TitleEvent.SetWatched(24))
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, s.values()?.status)
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
            s.tapAction()
            advanceUntilIdle()
            assertEquals(before, s.values())
            assertNull(s.message)
        }

    @Test
    fun `W9 SetWatched above the total is accepted and completes the title`() =
        test { s ->
            s.title.onEvent(TitleEvent.SetWatched(30))
            advanceUntilIdle()
            assertEquals(30, s.values()?.watchedEpisodes)
            assertEquals(ListStatus.Completed, s.values()?.status)
        }

    @Test
    fun `the Undo outlives the page`() =
        test { s ->
            val before = s.values()
            s.title.onEvent(TitleEvent.SetWatched(24))
            advanceUntilIdle()
            s.title.viewModelScope.cancel()
            s.tapAction()
            advanceUntilIdle()
            assertEquals(before, s.values())
        }

    @Test
    fun `a completing step is written and says so although its page is closed before the write is applied`() =
        test(slowWrites = true) { s ->
            s.title.onEvent(TitleEvent.SetWatched(24))
            s.title.viewModelScope.cancel()
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, s.values()?.status)
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
        }

    @Test
    fun `AddToList adds the title as Planned and says so`() =
        test(id = FixtureAnime.notOnListAiring.id) { s ->
            assertNull(s.shown.page.entry)
            s.title.onEvent(TitleEvent.AddToList)
            advanceUntilIdle()
            assertEquals(ListStatus.Planned, s.values()?.status)
            assertEquals(
                ListStatus.Planned,
                s.shown.page.entry
                    ?.status,
            )
            assertEquals(
                ShellMessage(ShellMessage.Text.AddedToPlanned, ShellMessage.Action.Change(FixtureAnime.notOnListAiring.id)),
                s.message,
            )
            s.tapAction()
            assertEquals(FixtureAnime.notOnListAiring.id, s.messages.editRequest.value)
            assertEquals(ListStatus.Planned, s.values()?.status)
        }

    @Test
    fun `the synopsis expands and collapses, and survives a rebuilt page and process death`() {
        val saved = SavedStateHandle()
        test(saved = saved) { s ->
            s.title.onEvent(TitleEvent.ExpandSynopsis)
            assertTrue(s.shown.moment.synopsisExpanded)
            s.title.onEvent(TitleEvent.SetWatched(13))
            advanceUntilIdle()
            assertTrue(s.shown.moment.synopsisExpanded)
        }
        test(saved = SavedStateHandle(mapOf("synopsisExpanded" to saved.get<Boolean>("synopsisExpanded")))) { s ->
            assertTrue(s.shown.moment.synopsisExpanded)
            s.title.onEvent(TitleEvent.CollapseSynopsis)
            assertEquals(false, s.shown.moment.synopsisExpanded)
        }
    }

    @Test
    fun `a failed load shows the error, and Retry loads the page`() =
        test(before = { demo.server.offline.value = true }) { s ->
            assertEquals(DataError.NoConnection, (s.state as TitleState.Failed).error)
            s.graph.demo.server.offline.value = false
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)
        }

    @Test
    fun `a list that cannot be read fails the page, and Retry shows it and follows the list again`() =
        test(before = { listUnreadable = true }) { s ->
            assertEquals(DataError.StorageError, (s.state as TitleState.Failed).error)

            s.graph.listUnreadable = false
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(12, s.watched())
            s.graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            assertEquals(13, s.watched())
        }

    @Test
    fun `a failed refresh with the details shown keeps the page and says so`() =
        test { s ->
            s.graph.demo.server.offline.value = true
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)
            assertEquals(ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection)), s.message)
        }

    @Test
    fun `the session is unknown, not signed out, until it is read`() = test(settle = false) { s -> assertNull(s.title.state.value.session) }

    @Test
    fun `an unknown title is not found`() =
        test(id = AnimeId(999_999_999)) { s ->
            assertEquals(DataError.NotFound, (s.state as TitleState.Failed).error)
        }

    @Test
    fun `the session reaches the page's state`() =
        test { s ->
            assertEquals(Session.SignedIn("kiroku_demo"), s.state.session)
            s.graph.demo.server.sessionExpired.value = true
            advanceUntilIdle()
            assertTrue(s.state.sessionExpired)
        }

    @Test
    fun `the events the route handles change nothing here`() =
        test { s ->
            val before = s.state
            listOf(
                TitleEvent.Back,
                TitleEvent.OpenOnMal,
                TitleEvent.Share,
                TitleEvent.Edit,
                TitleEvent.OpenRelated(FixtureAnime.prequel.id),
                TitleEvent.SeeAllRelated,
                TitleEvent.OpenDetails,
                TitleEvent.OpenRecommendations,
                TitleEvent.SignIn,
            ).forEach(s.title::onEvent)
            advanceUntilIdle()
            assertEquals(before, s.state)
        }

    @Test
    fun `a wait for the details shows its indicator only once it has lasted a second`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            showing(title.state)
            val shown = { title.state.value as TitleState.Shown }
            runCurrent()
            assertNull(shown().page.details)
            assertFalse(shown().detailsWaitShown)
            advanceTimeBy(PATIENCE_MILLIS - 1)
            runCurrent()
            assertFalse(shown().detailsWaitShown)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(shown().detailsWaitShown)
            assertNull(shown().page.details)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, shown().page.details)
            scope.cancel()
        }

    @Test
    fun `the page reads its sources only while it is shown, and shows what changed meanwhile when it is shown again`() =
        test { s ->
            assertTrue(s.graph.readers > 0)
            s.screen.cancel()
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            runCurrent()
            assertEquals(0, s.graph.readers)
            s.graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            assertEquals(0, s.graph.readers)
            s.screen = showing(s.title.state)
            assertEquals(13, s.watched())
            assertTrue(s.graph.readers > 0)
        }

    @Test
    fun `the details are refreshed once, not again when the page is shown again`() =
        test(before = { demo.server.offline.value = true }) { s ->
            assertTrue(s.state is TitleState.Failed)
            assertEquals(1, s.graph.refreshes)
            s.graph.demo.server.offline.value = false
            s.screen.cancel()
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            runCurrent()
            assertEquals(0, s.graph.readers)
            s.screen = showing(s.title.state)
            runCurrent()
            advanceUntilIdle()
            assertEquals(1, s.graph.refreshes)
            assertTrue(s.state is TitleState.Failed)
        }

    @Test
    fun `nothing is read or refreshed before the page is first shown`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            advanceUntilIdle()
            assertEquals(0, graph.readers)
            assertEquals(0, graph.refreshes)
            assertEquals(TitleState.Loading(null), title.state.value)
            showing(title.state)
            runCurrent()
            assertTrue(graph.readers > 0)
            assertEquals(1, graph.refreshes)
            scope.cancel()
        }

    // Section 9.4: opened from a row, the page has the summary before the store answers.
    @Test
    fun `a page opened with a summary shows it at once, before the store answers`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val summary = summary(graph)
            graph.readsHeld = true
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list), summary)
            val first = title.state.value as TitleState.Shown
            assertEquals(FixtureAnime.exactToWatch, first.page.anime)
            assertEquals(12, first.page.entry?.watchedEpisodes)
            assertNull(first.page.details)
            showing(title.state)
            advanceTimeBy(PATIENCE_MILLIS - 1)
            runCurrent()
            val held = (title.state.value as TitleState.Shown).page
            assertEquals(first.page.entry, held.entry)
            assertNull(held.details)
            graph.readsHeld = false
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, (title.state.value as TitleState.Shown).page.details)
            scope.cancel()
        }

    // A title opened from a related row is not stored yet: the page stays, and a failed refresh is a message.
    @Test
    fun `a page opened with a summary keeps it while the store does not hold the title, and after a failed refresh`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope).apply { titlesUnstored.value = true }
            val anime = FixtureAnime.notOnListAiring
            val summary = TitleSummary(anime, null, null, graph.session.observeSession().first())
            val messages = ShellMessages(scope, graph.list)
            val title = title(graph, anime.id, SavedStateHandle(), messages, summary)
            showing(title.state)
            advanceUntilIdle()
            assertEquals(anime, (title.state.value as TitleState.Shown).page.anime)
            assertNull(messages.current.value)

            graph.demo.server.offline.value = true
            title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            val shown = title.state.value as TitleState.Shown
            assertEquals(anime, shown.page.anime)
            assertFalse(shown.detailsWaitShown)
            assertEquals(ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection)), messages.current.value?.message)
            scope.cancel()
        }

    // A related row knows less of a title than the list entry and the store do, which win as soon as they are known.
    @Test
    fun `the list entry's and the store's title win over a related row's, also for the summary the page hands on`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val entries =
                graph.list
                    .observeEntries()
                    .first()
                    .associateBy { it.anime.id }
            val full = FixtureAnime.exactToWatch
            val partial = full.copy(meanScore = null, rank = null, malEpisodes = null)
            val details = FixtureDetails.all.single { it.id == FixtureAnime.premiereYear.id }
            val fromRow =
                details.copy(related = details.related.map { if (it.anime.id == full.id) it.copy(anime = partial) else it })
            val session = graph.session.observeSession().first()
            val page = pageSummary(fromRow, entries, graph.airing.observeAiring().first())
            val premiere = TitleSummary(FixtureAnime.premiereYear, entries[FixtureAnime.premiereYear.id], null, session, page)
            graph.readsHeld = true
            val parent = title(graph, FixtureAnime.premiereYear.id, SavedStateHandle(), ShellMessages(scope, graph.list), premiere)
            assertTrue(fromRow.related.any { it.anime == partial })
            assertEquals(full, parent.summaryOf(full.id)?.anime)
            assertEquals(entries[full.id], parent.summaryOf(full.id)?.entry)

            val opened = TitleSummary(partial, entries[full.id], null, session)
            val title = title(graph, full.id, SavedStateHandle(), ShellMessages(scope, graph.list), opened)
            assertEquals(full, (title.state.value as TitleState.Shown).page.anime)
            graph.readsHeld = false
            graph.titlesUnstored.value = true
            showing(title.state)
            advanceUntilIdle()
            assertEquals(full, (title.state.value as TitleState.Shown).page.anime)
            scope.cancel()
        }

    // What the List shows of the title.
    private suspend fun summary(graph: TestGraph): TitleSummary {
        val entry = graph.list.observeEntry(FixtureAnime.exactToWatch.id).first()!!
        return TitleSummary(
            entry.anime,
            entry,
            graph.airing.observeAiring().first()[entry.anime.id],
            graph.session.observeSession().first(),
        )
    }

    private fun title(
        graph: TestGraph,
        id: AnimeId,
        saved: SavedStateHandle,
        messages: ShellMessages,
        opened: TitleSummary? = null,
    ) = TitleViewModel(id, graph.anime, graph.list, graph.airing, graph.session, graph.clock, graph.titleLanguage, saved, messages, opened)

    private companion object {
        const val STOPPED_AFTER_MILLIS = 5_001L
        const val PATIENCE_MILLIS = 1_000L
        val DETAILS_LATENCY = 3.seconds
    }
}

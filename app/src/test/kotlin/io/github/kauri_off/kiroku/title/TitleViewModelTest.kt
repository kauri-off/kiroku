package io.github.kauri_off.kiroku.title

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.titleAiring
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.list.ListViewModel
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
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
        var screen: Job = test.showing(title.state, title.shown)

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

    // Section 7.2: the failure stands where the details would be, and is also said by a message on the first screen.
    @Test
    fun `details that could not load show the failure under the page until Retry loads them, and say so once`() =
        test(before = { demo.server.failingLoads.value = true }) { s ->
            assertEquals(FixtureAnime.exactToWatch, s.shown.page.anime)
            assertEquals(DetailsWait.Failed(DataError.ServerError), s.shown.detailsWait)
            assertEquals(ShellMessage(ShellMessage.Text.RefreshFailed(DataError.ServerError)), s.message)
            s.messages.ended(s.messages.current.value!!, actionPerformed = false)
            s.graph.demo.server.failingLoads.value = false
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            assertEquals(DetailsWait.Failed(DataError.ServerError), s.shown.detailsWait)
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)
            assertNull(s.message)
        }

    @Test
    fun `a failure stays while the list changes under it`() =
        test(before = { demo.server.failingLoads.value = true }) { s ->
            val failed = DetailsWait.Failed(DataError.ServerError)
            assertEquals(failed, s.shown.detailsWait)
            s.graph.demo.server.failingLoads.value = false
            s.graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            assertEquals(13, s.watched())
            assertEquals(failed, s.shown.detailsWait)
            s.graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactCaughtUp))
            advanceUntilIdle()
            assertEquals(failed, s.shown.detailsWait)
        }

    // The connection's return repeats a failure shown in place as a Retry would: the wait at once, and no message.
    @Test
    fun `details that failed in place are repeated as a Retry when the connection returns`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            graph.demo.server.offline.value = true
            val messages = ShellMessages(scope, graph.list)
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), messages)
            showing(title.state, title.shown)
            val wait = { (title.state.value as TitleState.Shown).detailsWait }
            advanceUntilIdle()
            val failed = DetailsWait.Failed(DataError.NoConnection)
            assertEquals(failed, wait())
            messages.ended(messages.current.value!!, actionPerformed = false)
            graph.connectionSays.value = true
            runCurrent()
            assertEquals(DetailsWait.Loading, wait())
            advanceTimeBy(DETAILS_LATENCY + 1.seconds)
            runCurrent()
            assertEquals(failed, wait())
            assertNull(messages.current.value)
            title.viewModelScope.cancel()
            scope.cancel()
        }

    // With the details shown, the repeat runs as one the page started: no wait shown at once, a failure said by a message.
    @Test
    fun `a failed refresh of details shown is repeated as the page's own when the connection returns`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone)
            val graph = TestGraph(Demo(Fixtures.reference, scope, DETAILS_LATENCY, clock))
            val messages = ShellMessages(scope, graph.list)
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), messages)
            var screen = showing(title.state, title.shown)
            val wait = { (title.state.value as TitleState.Shown).detailsWait }
            advanceUntilIdle()
            screen.cancel()
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            clock.instant += Duration.ofDays(1)
            graph.demo.server.offline.value = true
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceTimeBy(DETAILS_LATENCY + 1.seconds)
            runCurrent()
            val noConnection = ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection))
            assertEquals(noConnection, messages.current.value?.message)
            assertEquals(FixtureDetails.full, (title.state.value as TitleState.Shown).page.details)
            messages.ended(messages.current.value!!, actionPerformed = false)
            graph.connectionSays.value = true
            runCurrent()
            assertEquals(DetailsWait.Brief, wait())
            assertEquals(3, graph.requested.count { it == FixtureAnime.exactToWatch.id })
            advanceTimeBy(DETAILS_LATENCY + 1.seconds)
            runCurrent()
            assertEquals(noConnection, messages.current.value?.message)
            screen.cancel()
            title.viewModelScope.cancel()
            scope.cancel()
        }

    // A wait the person started needs no second's grace; a Retry that fails says so in place, with no message.
    @Test
    fun `a Retry shows the indicator at once, and one that fails again shows the failure again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            graph.demo.server.offline.value = true
            val messages = ShellMessages(scope, graph.list)
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), messages)
            showing(title.state, title.shown)
            val wait = { (title.state.value as TitleState.Shown).detailsWait }
            advanceUntilIdle()
            val failed = DetailsWait.Failed(DataError.NoConnection)
            assertEquals(failed, wait())
            messages.ended(messages.current.value!!, actionPerformed = false)
            title.onEvent(TitleEvent.Retry)
            runCurrent()
            assertEquals(DetailsWait.Loading, wait())
            advanceUntilIdle()
            assertEquals(failed, wait())
            assertNull(messages.current.value)
            scope.cancel()
        }

    @Test
    fun `a pulled refresh shows that it runs, and loads the details of a page whose load failed`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            graph.demo.server.failingLoads.value = true
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            showing(title.state, title.shown)
            val shown = { title.state.value as TitleState.Shown }
            advanceUntilIdle()
            graph.demo.server.failingLoads.value = false
            title.onEvent(TitleEvent.Refresh)
            runCurrent()
            assertTrue(shown().refreshing)
            assertEquals(DetailsWait.Brief, shown().detailsWait)
            advanceTimeBy(PATIENCE_MILLIS)
            runCurrent()
            assertEquals(DetailsWait.Brief, shown().detailsWait)
            advanceUntilIdle()
            assertEquals(false, shown().refreshing)
            assertEquals(FixtureDetails.full, shown().page.details)
            scope.cancel()
        }

    @Test
    fun `a title that cannot be loaded at all fails the whole page, and Retry shows its loading state at once`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val title = title(graph, AnimeId(999_999_999), SavedStateHandle(), ShellMessages(scope, graph.list))
            showing(title.state, title.shown)
            advanceUntilIdle()
            assertEquals(DataError.NotFound, (title.state.value as TitleState.Failed).error)
            title.onEvent(TitleEvent.Retry)
            runCurrent()
            assertTrue(title.state.value is TitleState.Loading)
            scope.cancel()
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
    fun `a title store that cannot be read fails the page, and Retry reads it again`() =
        test(before = { titlesUnreadable.value = true }) { s ->
            assertEquals(DataError.StorageError, (s.state as TitleState.Failed).error)

            s.graph.titlesUnreadable.value = false
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)

            s.graph.titlesUnreadable.value = true
            advanceUntilIdle()
            assertEquals(DataError.StorageError, (s.state as TitleState.Failed).error)
        }

    // The refresh after a store that cannot be read reads the stored details again, which fails too.
    @Test
    fun `a title store that cannot be read and a refresh that fails show the error state, not a crash`() =
        test(before = {
            titlesUnreadable.value = true
            demo.server.offline.value = true
        }) { s ->
            assertEquals(DataError.StorageError, (s.state as TitleState.Failed).error)
            s.title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            assertEquals(DataError.StorageError, (s.state as TitleState.Failed).error)
            assertEquals(2, s.graph.refreshes)
        }

    // Each build of the page reads the clock once for its moment, so the clock's reads count the builds.
    @Test
    fun `an airing change of a title the page does not show builds nothing, and one of a title it shows builds it`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val changed = MutableStateFlow(emptyMap<AnimeId, Airing>())
            val airing =
                object : AiringRepository by graph.airing {
                    override fun observeAiring() = combine(graph.airing.observeAiring(), changed) { stored, changed -> stored + changed }

                    override fun observePageAiring(page: Anime) =
                        combine(graph.airing.observePageAiring(page), changed) { stored, changed -> stored + changed }
                }
            val clock = CountedClock(graph.clock)
            val title =
                TitleViewModel(
                    FixtureAnime.exactToWatch.id,
                    graph.anime,
                    graph.list,
                    airing,
                    graph.session,
                    graph.connection,
                    clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    ShellMessages(scope, graph.list),
                )
            showing(title.state, title.shown)
            advanceUntilIdle()
            val shown = shownTitles(FixtureAnime.exactToWatch.id, FixtureDetails.full)
            val other =
                Fixtures.reference.entries
                    .first { it.anime.id !in shown }
                    .anime.id
            val someAiring = Fixtures.reference.airing.getValue(FixtureAnime.estimatedToWatch.id)

            var reads = clock.reads
            changed.value = mapOf(other to someAiring)
            advanceUntilIdle()
            assertEquals("builds for another title's airing", 0, clock.reads - reads)

            reads = clock.reads
            changed.value = mapOf(FixtureAnime.exactToWatch.id to someAiring)
            advanceUntilIdle()
            assertTrue("builds for the page's own airing", clock.reads > reads)
            scope.cancel()
        }

    // Every state the page passes through, also those a later one replaces at once.
    @Test
    fun `a related card shows its list status in the first state that shows it`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            val states = mutableListOf<TitleState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { title.state.collect { states += it } }
            showing(title.state, title.shown)
            advanceUntilIdle()
            val onList = Fixtures.reference.entries.associate { it.anime.id to it.status }
            val withDetails = states.filterIsInstance<TitleState.Shown>().filter { it.page.details != null }
            assertTrue(withDetails.isNotEmpty())
            for (state in withDetails) {
                for (card in state.page.related) {
                    assertEquals(card.title, onList[card.related.anime.id], card.status)
                }
                for (card in state.page.chain) {
                    assertEquals(card.title, onList[card.anime.id], card.status)
                }
            }
            assertTrue(
                withDetails
                    .last()
                    .page.chain
                    .any { !it.current && it.status != null },
            )
            scope.cancel()
        }

    // A title on the list that MAL gives no score: the List hands on whether its page was read.
    @Test
    fun `a list title MAL gives no score shows its dash in the first frame once its page was read`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val id = FixtureAnime.premiereTimeLater.id
            val messages = ShellMessages(scope, graph.list)
            val list =
                ListViewModel(
                    graph.list,
                    graph.anime,
                    graph.airing,
                    graph.session,
                    graph.connection,
                    graph.preferences,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    messages,
                    graph.background,
                )
            showing(list.state, list.shown)
            advanceUntilIdle()

            fun firstFrame(): MalScore {
                graph.readsHeld = true
                val title = title(graph, id, SavedStateHandle(), messages, checkNotNull(list.summaryOf(id)))
                return (title.state.value as TitleState.Shown).page.score.also { graph.readsHeld = false }
            }
            assertEquals("never read", MalScore.NotKnownYet, firstFrame())
            assertEquals(Outcome.Success(Unit), graph.anime.refresh(id))
            advanceUntilIdle()
            assertEquals("read before", MalScore.None, firstFrame())
            scope.cancel()
        }

    // The page reads the entries of the titles it shows in one read: itself, its related titles and its watch order.
    @Test
    fun `the page reads the list entries of the titles it shows in one read, not one per title and not the list`() =
        test { s ->
            val shown = setOf(FixtureAnime.exactToWatch.id) + FixtureDetails.full.related.map { it.anime.id } + chain
            assertEquals(shown, s.graph.entryQueries.last())
            assertEquals(emptySet<AnimeId>(), s.graph.entriesRead)
            assertEquals(0, s.graph.wholeListReads)
        }

    @Test
    fun `a failed pulled refresh with the details shown keeps the page and says so`() =
        test { s ->
            s.graph.demo.server.offline.value = true
            s.title.onEvent(TitleEvent.Refresh)
            advanceUntilIdle()
            assertEquals(FixtureDetails.full, s.shown.page.details)
            assertEquals(DetailsWait.Brief, s.shown.detailsWait)
            assertEquals(false, s.shown.refreshing)
            assertEquals(ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection)), s.message)
        }

    @Test
    fun `A12 a title outside the list shows exact airing once shown, and a pull asks for it again however fresh`() =
        test(id = FixtureAnime.notOnListAiring.id) { s ->
            val anime = FixtureAnime.notOnListAiring
            val source = s.graph.demo.airingSource
            val now = s.graph.clock.instant()
            assertEquals(listOf(setOf(anime.id)), source.requests)
            assertEquals(titleAiring(anime, null, Fixtures.reference.airing[anime.id], now).phase, s.shown.page.airing)
            assertEquals(AiredCount.Exact(7), (s.shown.page.airing as AiringPhase.Aired).aired)

            val schedule = Fixtures.reference.schedules.getValue(anime.id)
            val moved =
                schedule.copy(
                    episodes =
                        schedule.episodes.map {
                            if (it.number ==
                                8
                            ) {
                                it.copy(airsAt = now.minusSeconds(HOUR))
                            } else {
                                it
                            }
                        },
                )
            source.schedules += anime.id to moved
            s.title.onEvent(TitleEvent.Refresh)
            advanceUntilIdle()
            assertEquals(2, source.requests.size)
            assertEquals(AiredCount.Exact(8), (s.shown.page.airing as AiringPhase.Aired).aired)
            assertEquals(false, s.shown.refreshing)
            assertNull(s.message)
        }

    @Test
    fun `a page opened with its title known asks for its airing beside its details, not after them`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            graph.titlesUnstored.value = true
            val anime = FixtureAnime.notOnListAiring
            val summary = TitleSummary(anime, null, null, graph.session.observeSession().first())
            val title = title(graph, anime.id, SavedStateHandle(), ShellMessages(scope, graph.list), summary)
            showing(title.state, title.shown)
            runCurrent()
            assertEquals(listOf(setOf(anime.id)), graph.demo.airingSource.requests)
            assertEquals(emptyList<AnimeId>(), graph.refreshed)
            advanceUntilIdle()
            scope.cancel()
        }

    @Test
    fun `a tracked title's page shows the List's own result, as its row does`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val messages = ShellMessages(scope, graph.list)
            val list =
                ListViewModel(
                    graph.list,
                    graph.anime,
                    graph.airing,
                    graph.session,
                    graph.connection,
                    graph.preferences,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    messages,
                    graph.background,
                )
            showing(list.state, list.shown)
            advanceUntilIdle()
            for (anime in listOf(FixtureAnime.exactToWatch, FixtureAnime.estimatedToWatch, FixtureAnime.premiereTimeSoon)) {
                val title = title(graph, anime.id, SavedStateHandle(), messages)
                val screen = showing(title.state, title.shown)
                advanceUntilIdle()
                val row = checkNotNull(list.summaryOf(anime.id))
                assertSame(anime.titles.romaji, row.airing, title.summaryOf(anime.id)?.airing)
                val page = (title.state.value as TitleState.Shown).page
                assertEquals(
                    anime.titles.romaji,
                    titleAiring(anime, row.entry?.values, row.airing, graph.clock.instant()).phase,
                    page.airing,
                )
                screen.cancel()
            }
            scope.cancel()
        }

    @Test
    fun `with AniList failing a page shows the estimate and no message, also after a pull`() =
        test(id = FixtureAnime.notOnListAiring.id, before = { demo.airingSource.failure = DataError.ServerError }) { s ->
            assertEquals(AiredCount.Estimated(7), (s.shown.page.airing as AiringPhase.Aired).aired)
            assertNull(s.message)
            s.title.onEvent(TitleEvent.Refresh)
            advanceUntilIdle()
            assertEquals(2, s.graph.demo.airingSource.requests.size)
            assertEquals(AiredCount.Estimated(7), (s.shown.page.airing as AiringPhase.Aired).aired)
            assertNull(s.message)
        }

    // Section 6.2: the airing is a part of the page, read again at its next pull.
    @Test
    fun `a failed airing read shows the page without its airing and no message, and a Retry or a pull reads it again`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            val failing = MutableStateFlow(true)
            val airing =
                object : AiringRepository by graph.airing {
                    override fun observePageAiring(page: Anime) =
                        combine(graph.airing.observePageAiring(page), failing) { airing, failing ->
                            if (failing) throw DataException(DataError.StorageError)
                            airing
                        }
                }
            val messages = ShellMessages(scope, graph.list)
            val anime = FixtureAnime.exactToWatch
            val title =
                TitleViewModel(
                    anime.id,
                    graph.anime,
                    graph.list,
                    airing,
                    graph.session,
                    graph.connection,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    messages,
                )
            showing(title.state, title.shown)
            advanceUntilIdle()
            val entry = Fixtures.reference.entries.single { it.anime.id == anime.id }
            val shown = { title.state.value as TitleState.Shown }
            val unread = titleAiring(anime, entry.values, null as Airing?, graph.clock.instant()).phase
            assertEquals(unread, shown().page.airing)
            assertEquals(FixtureDetails.full, shown().page.details)
            assertNull(messages.current.value)

            val expected = titleAiring(anime, entry.values, Fixtures.reference.airing.getValue(anime.id), graph.clock.instant()).phase
            for (again in listOf(TitleEvent.Retry, TitleEvent.Refresh)) {
                failing.value = false
                advanceUntilIdle()
                assertEquals("$again", unread, shown().page.airing)
                title.onEvent(again)
                advanceUntilIdle()
                assertEquals("$again", expected, shown().page.airing)
                assertNull(messages.current.value)
                failing.value = true
                advanceUntilIdle()
                assertEquals("$again", unread, shown().page.airing)
            }
            scope.cancel()
        }

    @Test
    fun `a pull's indicator ends with the details while AniList does not answer, and the answer updates the page when it lands`() =
        test(id = FixtureAnime.notOnListAiring.id) { s ->
            val anime = FixtureAnime.notOnListAiring
            val source = s.graph.demo.airingSource
            val schedule = Fixtures.reference.schedules.getValue(anime.id)
            val now = s.graph.clock.instant()
            source.schedules +=
                anime.id to
                schedule.copy(episodes = schedule.episodes.map { if (it.number == 8) it.copy(airsAt = now.minusSeconds(HOUR)) else it })
            val answer = CompletableDeferred<Unit>()
            source.holding = answer
            s.title.onEvent(TitleEvent.Refresh)
            advanceUntilIdle()
            assertEquals(2, source.requests.size)
            assertEquals(false, s.shown.refreshing)
            assertEquals(DetailsWait.Brief, s.shown.detailsWait)
            assertEquals(AiredCount.Exact(7), (s.shown.page.airing as AiringPhase.Aired).aired)
            assertNull(s.message)
            answer.complete(Unit)
            advanceUntilIdle()
            assertEquals(AiredCount.Exact(8), (s.shown.page.airing as AiringPhase.Aired).aired)
        }

    // The page's own airing ask, which AniList never answers, does not hold a walk a short hide cut.
    @Test
    fun `a page shown again within its stop timeout mid-walk finishes its chain while AniList does not answer`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY, airingStored = false))
            graph.demo.airingSource.holding = CompletableDeferred()
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            var screen = showing(title.state, title.shown)
            advanceTimeBy(DETAILS_LATENCY.inWholeMilliseconds + 1)
            runCurrent()
            assertEquals(listOf(setOf(FixtureAnime.exactToWatch.id)), graph.demo.airingSource.requests)
            screen.cancel()
            runCurrent()
            advanceTimeBy(ROTATION_MILLIS)
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ownAndChain, graph.refreshed)
            screen.cancel()
            title.viewModelScope.cancel()
            scope.cancel()
        }

    // A12's common case: a page whose details are still fresh asks for its airing alone, once it is stale, never on a rotation.
    @Test
    fun `a page with fresh details asks for its stale airing when shown, and nothing on a rotation`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone)
            val graph = TestGraph(Demo(Fixtures.reference, scope, clock = clock))
            val anime = FixtureAnime.notOnListAiring
            val title = title(graph, anime.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            val requests = { graph.demo.airingSource.requests.size }
            var screen = showing(title.state, title.shown)
            advanceUntilIdle()
            assertEquals(1, requests())
            val loads = graph.refreshes

            screen.cancel()
            runCurrent()
            advanceTimeBy(ROTATION_MILLIS)
            screen = showing(title.state, title.shown)
            advanceUntilIdle()
            assertEquals(1, requests())

            screen.cancel()
            runCurrent()
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            runCurrent()
            clock.instant = Fixtures.reference.now.plus(Duration.ofMinutes(20))
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(2, requests())
            assertEquals(loads, graph.refreshes)
            screen.cancel()
            scope.cancel()
        }

    // Opened from Search: no title is stored before the details, so the page asks and reads with the opener's.
    @Test
    fun `a page opened with a summary of a title not stored shows the exact airing fetched for it`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope).apply { titlesUnstored.value = true }
            val anime = FixtureAnime.notOnListAiring
            val summary = TitleSummary(anime, null, null, graph.session.observeSession().first())
            val title = title(graph, anime.id, SavedStateHandle(), ShellMessages(scope, graph.list), summary)
            showing(title.state, title.shown)
            advanceUntilIdle()
            assertEquals(AiredCount.Exact(7), ((title.state.value as TitleState.Shown).page.airing as AiringPhase.Aired).aired)
            scope.cancel()
        }

    // Signed out, the live graph refuses every airing ask as for a list that ended; the demo has no such gate.
    @Test
    fun `signed out, a pull whose airing ask is refused shows nothing of it and ends with the details`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
            graph.session.signOut()
            advanceUntilIdle()
            val ended = Outcome.Failure(DataError.ListEnded)
            val airing =
                object : AiringRepository by graph.airing {
                    override suspend fun refresh(page: Anime) = ended

                    override suspend fun refreshStale(page: Anime) = ended
                }
            val messages = ShellMessages(scope, graph.list)
            val title =
                TitleViewModel(
                    FixtureAnime.notOnListAiring.id,
                    graph.anime,
                    graph.list,
                    airing,
                    graph.session,
                    graph.connection,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    messages,
                )
            showing(title.state, title.shown)
            advanceUntilIdle()
            val loads = graph.refreshes
            title.onEvent(TitleEvent.Refresh)
            advanceUntilIdle()
            assertEquals(loads + 1, graph.refreshes)
            val shown = title.state.value as TitleState.Shown
            assertEquals(false, shown.refreshing)
            assertEquals(DetailsWait.Brief, shown.detailsWait)
            assertNull(messages.current.value)
            scope.cancel()
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
            assertEquals(Fixtures.reference.session, s.state.session)
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
            showing(title.state, title.shown)
            val shown = { title.state.value as TitleState.Shown }
            runCurrent()
            assertNull(shown().page.details)
            assertEquals(DetailsWait.Brief, shown().detailsWait)
            advanceTimeBy(PATIENCE_MILLIS - 1)
            runCurrent()
            assertEquals(DetailsWait.Brief, shown().detailsWait)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(DetailsWait.Loading, shown().detailsWait)
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
            s.screen = showing(s.title.state, s.title.shown)
            assertEquals(13, s.watched())
            assertTrue(s.graph.readers > 0)
        }

    @Test
    fun `the details are refreshed once, not again when the page is shown again while they are fresh`() =
        test { s ->
            assertEquals(FixtureDetails.full, s.shown.page.details)
            assertEquals(1, s.graph.refreshed.count { it == FixtureAnime.exactToWatch.id })
            s.screen.cancel()
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            runCurrent()
            assertEquals(0, s.graph.readers)
            s.screen = showing(s.title.state, s.title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(1, s.graph.refreshed.count { it == FixtureAnime.exactToWatch.id })
            assertEquals(FixtureDetails.full, s.shown.page.details)
        }

    private val chain = listOf(FixtureAnime.prequel, FixtureAnime.exactToWatch, FixtureAnime.premiereYear).map { it.id }

    private fun TitleState.chain() = (this as TitleState.Shown).page.chain.map { it.anime.id }

    // One request per title of the chain, the nearest first and a sequel before a prequel, after the page's own.
    @Test
    fun `a chain is walked once, one request per title, and read from the store afterwards`() =
        test { s ->
            val walked = listOf(FixtureAnime.exactToWatch, FixtureAnime.premiereYear, FixtureAnime.prequel).map { it.id }
            assertEquals(walked, s.graph.refreshed)
            assertEquals(chain, s.state.chain())
            assertEquals(chain.toSet(), s.graph.detailQueries.last())
            s.screen.cancel()
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            runCurrent()
            s.screen = showing(s.title.state, s.title.shown)
            runCurrent()
            advanceUntilIdle()
            val again = title(s.graph, FixtureAnime.premiereYear.id, SavedStateHandle(), s.messages)
            showing(again.state, again.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(walked, s.graph.refreshed)
            assertEquals(chain, s.state.chain())
            assertEquals(chain, again.state.value.chain())
        }

    @Test
    fun `nothing is fetched for a title that has no sequel or prequel`() =
        test(id = FixtureAnime.exactCaughtUp.id) { s ->
            assertEquals(listOf(FixtureAnime.exactCaughtUp.id), s.graph.refreshed)
            assertEquals(emptyList<AnimeId>(), s.state.chain())
        }

    // The walk stops at the failed title and the page keeps what the store tells, with no message; the next refresh
    // goes on from there, asking again only for what is not stored or is stale.
    @Test
    fun `a failure in the chain leaves the page as it was`() =
        test(before = { refreshFails.value = setOf(FixtureAnime.premiereYear.id) }) { s ->
            assertEquals(listOf(FixtureAnime.exactToWatch.id, FixtureAnime.premiereYear.id), s.graph.refreshed)
            assertEquals(chain, s.state.chain())
            assertEquals(FixtureDetails.full, s.shown.page.details)
            assertEquals(false, s.shown.chainLoading)
            assertEquals(false, s.shown.refreshing)
            assertNull(s.message)
            s.graph.refreshFails.value = emptySet()
            s.title.onEvent(TitleEvent.Refresh)
            advanceUntilIdle()
            assertEquals(
                listOf(
                    FixtureAnime.exactToWatch,
                    FixtureAnime.premiereYear,
                    FixtureAnime.exactToWatch,
                    FixtureAnime.premiereYear,
                    FixtureAnime.prequel,
                ).map { it.id },
                s.graph.refreshed,
            )
            assertEquals(chain, s.state.chain())
        }

    @Test
    fun `a failed walk is taken up again when the page is shown again, without the page's own fresh title`() =
        test(before = { refreshFails.value = setOf(FixtureAnime.prequel.id) }) { s ->
            s.graph.refreshFails.value = emptySet()
            s.screen.cancel()
            advanceTimeBy(STOPPED_AFTER_MILLIS)
            runCurrent()
            s.screen = showing(s.title.state, s.title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(
                listOf(FixtureAnime.exactToWatch, FixtureAnime.premiereYear, FixtureAnime.prequel, FixtureAnime.prequel).map { it.id },
                s.graph.refreshed,
            )
        }

    private val ownAndChain = listOf(FixtureAnime.exactToWatch, FixtureAnime.premiereYear, FixtureAnime.prequel).map { it.id }

    // The walk asks for the third season the moment the page's own details arrive; the page is left for that season.
    @Test
    fun `a page left for its sequel stops its walk, so no title is asked twice`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val messages = ShellMessages(scope, graph.list)
            val first = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), messages)
            val firstShown = showing(first.state, first.shown)
            advanceTimeBy(DETAILS_LATENCY.inWholeMilliseconds + 1)
            runCurrent()
            assertEquals(FixtureDetails.full, (first.state.value as TitleState.Shown).page.details)
            firstShown.cancel()
            val next = title(graph, FixtureAnime.premiereYear.id, SavedStateHandle(), messages)
            showing(next.state, next.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(listOf(FixtureAnime.exactToWatch, FixtureAnime.premiereYear, FixtureAnime.prequel).map { it.id }, graph.refreshed)
            assertEquals(chain, next.state.value.chain())
            scope.cancel()
        }

    @Test
    fun `a page left mid-walk asks nothing more, and its next show goes on without asking again for what is stored`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            var screen = showing(title.state, title.shown)
            advanceTimeBy(2 * DETAILS_LATENCY.inWholeMilliseconds + 1)
            runCurrent()
            assertEquals(ownAndChain.take(2), graph.refreshed)
            screen.cancel()
            advanceTimeBy(1.minutes.inWholeMilliseconds)
            runCurrent()
            assertEquals(ownAndChain.take(2), graph.refreshed)
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ownAndChain, graph.refreshed)
            assertEquals(chain, title.state.value.chain())
            screen.cancel()
            scope.cancel()
        }

    // A sign-out in another tab empties the stores while this page is in that tab's stack, no longer shown.
    @Test
    fun `a page that stops being shown mid-walk writes nothing more to the store, as across a sign-out`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            val screen = showing(title.state, title.shown)
            advanceTimeBy(DETAILS_LATENCY.inWholeMilliseconds + 1)
            runCurrent()
            screen.cancel()
            runCurrent()
            graph.session.signOut()
            advanceTimeBy(1.minutes.inWholeMilliseconds)
            runCurrent()
            assertEquals(ownAndChain.take(1), graph.refreshed)
            assertNull(graph.anime.observeDetails(FixtureAnime.premiereYear.id).first())
            assertNull(graph.anime.observeDetails(FixtureAnime.prequel.id).first())
            scope.cancel()
        }

    // A rotation mid-walk: the page is shown again within its stop timeout, so nothing but the walk itself resumes it.
    // The title asked for when the page was hidden is asked again, as the hide cancelled it.
    @Test
    fun `a page shown again within its stop timeout mid-walk finishes its chain`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            var screen = showing(title.state, title.shown)
            advanceTimeBy(DETAILS_LATENCY.inWholeMilliseconds + 1)
            runCurrent()
            screen.cancel()
            runCurrent()
            advanceTimeBy(ROTATION_MILLIS)
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ownAndChain, graph.refreshed)
            assertEquals(
                listOf(FixtureAnime.exactToWatch, FixtureAnime.premiereYear, FixtureAnime.premiereYear, FixtureAnime.prequel).map {
                    it.id
                },
                graph.requested,
            )
            screen.cancel()
            scope.cancel()
        }

    @Test
    fun `a page hidden before its own details arrive does not walk while hidden, and walks on its next show`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            var screen = showing(title.state, title.shown)
            advanceTimeBy(1.seconds.inWholeMilliseconds)
            runCurrent()
            screen.cancel()
            advanceTimeBy(1.minutes.inWholeMilliseconds)
            runCurrent()
            assertEquals(ownAndChain.take(1), graph.requested)
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ownAndChain, graph.requested)
            screen.cancel()
            scope.cancel()
        }

    // A store emptied while the page was hidden (a sign-out) holds not even the page's own page: a walk resumed then
    // reads no chain, and does not count as done, so the next show walks it.
    @Test
    fun `a walk that finds the page's own page gone is not done, and the next show walks the chain`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            var screen = showing(title.state, title.shown)
            advanceTimeBy(DETAILS_LATENCY.inWholeMilliseconds + 1)
            runCurrent()
            screen.cancel()
            runCurrent()
            graph.titlesUnstored.value = true
            advanceTimeBy(ROTATION_MILLIS)
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ownAndChain.take(1), graph.refreshed)
            screen.cancel()
            graph.titlesUnstored.value = false
            advanceTimeBy(1.minutes.inWholeMilliseconds)
            runCurrent()
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ownAndChain, graph.refreshed)
            screen.cancel()
            scope.cancel()
        }

    // Shown again after a long absence with a cut walk and a stale page of its own: the page's own refresh, then the
    // walk, never side by side.
    @Test
    fun `a cut walk shown again past its stop timeout over a stale page asks for each title once, after the page's own`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone)
            val graph = TestGraph(Demo(Fixtures.reference, scope, DETAILS_LATENCY, clock))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            var screen = showing(title.state, title.shown)
            advanceTimeBy(DETAILS_LATENCY.inWholeMilliseconds + 1)
            runCurrent()
            screen.cancel()
            advanceTimeBy(1.minutes.inWholeMilliseconds)
            runCurrent()
            clock.instant += Duration.ofHours(7)
            val before = graph.requested.size
            screen = showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ownAndChain, graph.requested.drop(before))
            assertEquals(ownAndChain.take(1) + ownAndChain, graph.refreshed)
            screen.cancel()
            scope.cancel()
        }

    // Freshness as for any page: past six hours only the airing title itself is stale, past a day the chain too.
    @Test
    fun `a stale stored page of the chain is asked for again and a fresh one is not`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone)
            val graph = TestGraph(Demo(Fixtures.reference, scope, kotlin.time.Duration.ZERO, clock))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            showing(title.state, title.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ownAndChain, graph.refreshed)
            clock.instant += Duration.ofHours(7)
            title.onEvent(TitleEvent.Refresh)
            advanceUntilIdle()
            assertEquals(ownAndChain + FixtureAnime.exactToWatch.id, graph.refreshed)
            clock.instant += Duration.ofHours(18)
            title.onEvent(TitleEvent.Refresh)
            advanceUntilIdle()
            assertEquals(ownAndChain + FixtureAnime.exactToWatch.id + ownAndChain, graph.refreshed)
            scope.cancel()
        }

    @Test
    fun `a walk shows its indicator only once it has lasted a second, and never once it has ended`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(Fixtures.reference, scope, latency = DETAILS_LATENCY))
            val title = title(graph, FixtureAnime.exactToWatch.id, SavedStateHandle(), ShellMessages(scope, graph.list))
            showing(title.state, title.shown)
            val shown = { title.state.value as TitleState.Shown }
            advanceTimeBy(DETAILS_LATENCY.inWholeMilliseconds)
            runCurrent()
            assertEquals(FixtureDetails.full, shown().page.details)
            assertEquals(false, shown().chainLoading)
            advanceTimeBy(PATIENCE_MILLIS - 1)
            runCurrent()
            assertEquals(false, shown().chainLoading)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(true, shown().chainLoading)
            advanceUntilIdle()
            assertEquals(false, shown().chainLoading)
            assertEquals(chain, title.state.value.chain())
            scope.cancel()
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
            showing(title.state, title.shown)
            runCurrent()
            assertTrue(graph.readers > 0)
            advanceUntilIdle()
            assertEquals(listOf(FixtureAnime.exactToWatch, FixtureAnime.premiereYear, FixtureAnime.prequel).map { it.id }, graph.refreshed)
            scope.cancel()
        }

    // Section 6.2: opened from a row, the page has the summary before the store answers.
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
            showing(title.state, title.shown)
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

    // A title from a related row is not stored yet: the page stays, and a failed refresh stands in for its details.
    @Test
    fun `a page opened with a summary keeps it while the store does not hold the title, and after a failed refresh`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope).apply { titlesUnstored.value = true }
            val anime = FixtureAnime.notOnListAiring
            val summary = TitleSummary(anime, null, null, graph.session.observeSession().first())
            val messages = ShellMessages(scope, graph.list)
            val title = title(graph, anime.id, SavedStateHandle(), messages, summary)
            showing(title.state, title.shown)
            advanceUntilIdle()
            assertEquals(anime, (title.state.value as TitleState.Shown).page.anime)
            assertNull(messages.current.value)

            graph.demo.server.offline.value = true
            title.onEvent(TitleEvent.Retry)
            advanceUntilIdle()
            val shown = title.state.value as TitleState.Shown
            assertEquals(anime, shown.page.anime)
            assertEquals(DetailsWait.Failed(DataError.NoConnection), shown.detailsWait)
            assertNull(messages.current.value)
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
            showing(title.state, title.shown)
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
    ) = TitleViewModel(
        id,
        graph.anime,
        graph.list,
        graph.airing,
        graph.session,
        graph.connection,
        graph.clock,
        graph.titleLanguage,
        saved,
        messages,
        opened,
    )

    private class CountedClock(
        private val inner: Clock,
    ) : Clock() {
        var reads = 0

        override fun instant(): Instant = inner.instant().also { reads++ }

        override fun getZone(): ZoneId = inner.zone

        override fun withZone(zone: ZoneId): Clock = inner.withZone(zone)
    }

    private companion object {
        const val STOPPED_AFTER_MILLIS = 5_001L
        const val ROTATION_MILLIS = 1_000L
        const val PATIENCE_MILLIS = 1_000L
        val DETAILS_LATENCY = 3.seconds
        const val HOUR = 3_600L
    }
}

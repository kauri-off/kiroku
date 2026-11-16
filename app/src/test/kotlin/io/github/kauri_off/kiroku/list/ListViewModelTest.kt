package io.github.kauri_off.kiroku.list

import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.demo.DemoPreferences
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.list.defaultSort
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.edit.EditSheetState
import io.github.kauri_off.kiroku.plain
import io.github.kauri_off.kiroku.premiereOn
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.title.TitlePage
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val set = Fixtures.reference
    private val today = set.now.atZone(FixtureTime.zone).toLocalDate()
    private val midnight = today.plusDays(1).atStartOfDay(FixtureTime.zone).toInstant()
    private val nextAirTime =
        set.airing.values
            .mapNotNull { it.nextEpisode?.airsAt?.takeIf { at -> at > set.now } }
            .min()

    private class Subject(
        val graph: TestGraph,
        val saved: SavedStateHandle,
        val messages: ShellMessages,
        val list: ListViewModel,
        private val test: TestScope,
    ) {
        var screen: Job = test.showing(list.state, list.shown)

        // What the screen shows once the events so far have been handled; a frame applies the typed filter first.
        val state: ListState
            get() {
                Snapshot.sendApplyNotifications()
                test.runCurrent()
                return list.state.value
            }

        val message: ShellMessage? get() = messages.current.value?.message

        // The snackbar's action, tapped wherever the user is by then.
        fun tapAction() = messages.ended(messages.current.value!!, actionPerformed = true)

        fun page(status: EntryStatus = state.tab): ListPage = (state.content as ListContent.Tabs).pages.getValue(status)

        fun ids(status: EntryStatus = state.tab): List<AnimeId> = page(status).rows.map { it.id }
    }

    private fun test(
        demo: (CoroutineScope) -> Demo = { Demo(Fixtures.reference, it) },
        saved: SavedStateHandle = SavedStateHandle(),
        lateObservers: Boolean = false,
        slowWrites: Boolean = false,
        unreadable: Boolean = false,
        preferences: PreferencesRepository? = null,
        airing: (AiringRepository) -> AiringRepository = { it },
        background: CoroutineDispatcher? = null,
        body: suspend TestScope.(Subject) -> Unit,
    ) = runTest(dispatcher) {
        val scope = foregroundScope()
        val shown = demo(scope)
        val graph = TestGraph(shown, lateObservers, slowWrites, preferences ?: shown.preferences).apply { listUnreadable = unreadable }
        val messages = ShellMessages(scope, graph.list)
        val list =
            ListViewModel(
                graph.list,
                graph.anime,
                airing(graph.airing),
                graph.session,
                graph.connection,
                graph.preferences,
                graph.clock,
                graph.titleLanguage,
                saved,
                messages,
                background ?: graph.background,
            )
        val subject = Subject(graph, saved, messages, list, this)
        advanceUntilIdle()
        body(subject)
        scope.cancel()
    }

    private suspend fun Subject.values(anime: Anime) =
        graph.list
            .observeEntry(anime.id)
            .first()
            ?.values

    private fun upNext(status: ListStatus = ListStatus.Watching) =
        order(set.entries.filter { it.status == status }, ListSort.UpNext, set.airing::get) { it.anime.titles.romaji }
            .map { it.anime.id }

    @Test
    fun `the list opens on Watching with every tab ordered once, by its default sort`() =
        test { s ->
            assertEquals(ListStatus.Watching, s.state.tab)
            assertEquals(upNext(), s.ids())
            ListStatus.entries.forEach { assertEquals(it.name, 0, s.page(it).ordering) }
            assertEquals(ListSort.LastUpdated, s.page(ListStatus.Planned).sort)
            assertEquals(set.entries.count { it.status == ListStatus.Planned }, s.ids(ListStatus.Planned).size)
            assertFalse(s.state.refreshing)
            assertFalse(s.state.sessionExpired)
        }

    @Test
    fun `a list that cannot be read shows the error state, and Retry shows the list and follows it again`() =
        test(unreadable = true) { s ->
            assertEquals(ListContent.Failed(ListFailure.Other), s.state.content)

            s.graph.listUnreadable = false
            s.list.onEvent(ListEvent.Retry)
            advanceUntilIdle()
            assertEquals(upNext(), s.ids())
            s.graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            val row = s.page().rows.single { it.id == FixtureAnime.exactToWatch.id }
            assertEquals(13, (row.line2 as Line2.Progress).watched)
        }

    // The view model holds no list after a failed read, but the store does: only a store read empty waits for its sync.
    @Test
    fun `Retry from a list that could not be read shows the stored list while its sync still runs`() =
        test(unreadable = true) { s ->
            assertEquals(ListContent.Failed(ListFailure.Other), s.state.content)
            s.graph.demo.server.latency = 10.seconds
            s.graph.listUnreadable = false

            s.list.onEvent(ListEvent.Retry)
            advanceTimeBy(1.seconds)

            assertEquals(set.entries.size, (s.state.content as ListContent.Tabs).pages.values.sumOf { it.rows.size })
            assertEquals(upNext(), s.ids())
            advanceUntilIdle()
        }

    @Test
    fun `a stale stored list that is read after its sync started is shown while the sync still runs`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(set, scope, latency = 10.seconds))
            graph.readsHeld = true
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
                    ShellMessages(scope, graph.list),
                    graph.background,
                )
            val screen = showing(list.state, list.shown)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(1, graph.syncs)
            assertEquals(ListContent.Loading, list.state.value.content)

            graph.readsHeld = false
            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(set.entries.size, (list.state.value.content as ListContent.Tabs).pages.values.sumOf { it.rows.size })
            advanceUntilIdle()
            screen.cancel()
            list.viewModelScope.cancel()
            scope.cancel()
        }

    @Test
    fun `a row without airing data and its title page judge a premiere by one day, Japan's, which ends before the device's here`() {
        val anime = FixtureAnime.premiereDate
        val premiere = (anime.startDate as PartialDate.Day).date
        val end = premiere.plusDays(1).atStartOfDay(FixtureTime.japan).toInstant()
        val clock = MovingClock(end.minusSeconds(1), FixtureTime.zone)
        test(
            demo = { Demo(set, it, clock = clock) },
            airing = { inner ->
                object : AiringRepository by inner {
                    override fun observeAiring(): Flow<Map<AnimeId, Airing>> = flowOf(emptyMap())
                }
            },
        ) { s ->
            val entry = checkNotNull(set.entryFor(anime.id))

            fun row() =
                s
                    .page(ListStatus.Planned)
                    .rows
                    .single { it.id == anime.id }
                    .line2

            fun page() = TitlePage.of(anime, entry, null, null, emptyMap(), TitleLanguage.Romaji, clock).airing.plain()
            assertEquals(Line2.Premieres(premiereOn(PartialDate.Day(premiere))), row())
            assertEquals(premiere, page())

            clock.instant = end
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Planned))

            assertEquals(Line2.Premieres(Premiere.Unannounced), row())
            assertEquals(Premiere.Unannounced, page())
        }
    }

    @Test
    fun `SelectTab changes the tab and never the order`() =
        test { s ->
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Planned))
            assertEquals(ListStatus.Planned, s.state.tab)
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Watching))
            ListStatus.entries.forEach { assertEquals(0, s.page(it).ordering) }
            assertEquals(upNext(), s.ids())
        }

    @Test
    fun `PlusOne writes a step, keeps the row in place and offers Undo of every value`() =
        test { s ->
            val before = s.ids()
            s.list.onEvent(ListEvent.PlusOne(FixtureAnime.exactToWatch.id))
            advanceUntilIdle()
            assertEquals(13, s.values(FixtureAnime.exactToWatch)?.watchedEpisodes)
            assertEquals(ShellMessage.Text.EpisodeWatched(13), s.message?.text)
            assertEquals(before, s.ids())
            s.tapAction()
            advanceUntilIdle()
            assertEquals(12, s.values(FixtureAnime.exactToWatch)?.watchedEpisodes)
            assertNull(s.message)
        }

    @Test
    fun `PlusOne is written and says so although the List is closed before the write is applied`() =
        test(slowWrites = true) { s ->
            s.list.onEvent(ListEvent.PlusOne(FixtureAnime.exactToWatch.id))
            s.list.viewModelScope.cancel()
            advanceUntilIdle()
            assertEquals(13, s.values(FixtureAnime.exactToWatch)?.watchedEpisodes)
            assertEquals(ShellMessage.Text.EpisodeWatched(13), s.message?.text)
        }

    @Test
    fun `PlusOne that reaches the total moves the title to Completed, and Undo puts it back in its place`() =
        test { s ->
            val anime = FixtureAnime.oneShortOfTotal
            val before = s.ids()
            val values = s.values(anime)
            s.list.onEvent(ListEvent.PlusOne(anime.id))
            advanceUntilIdle()
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
            assertEquals(before - anime.id, s.ids())
            assertEquals(anime.id, s.ids(ListStatus.Completed).last())
            s.tapAction()
            advanceUntilIdle()
            assertEquals(values, s.values(anime))
            assertEquals(before, s.ids())
        }

    @Test
    fun `W9 PlusOne counts on past the total and W7 completes the title`() =
        test { s ->
            val anime = FixtureAnime.oneShortOfTotal
            s.graph.list.write(ListWrite.Save(anime, s.values(anime)!!.copy(watchedEpisodes = 12)))
            s.list.onEvent(ListEvent.PlusOne(anime.id))
            advanceUntilIdle()
            assertEquals(13, s.values(anime)?.watchedEpisodes)
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
        }

    // Five taps before any of them is written: each counts, and Undo takes back only the last (W3).
    private fun plusOneBurst(lateObservers: Boolean) =
        test(lateObservers = lateObservers) { s ->
            val anime = FixtureAnime.exactToWatch
            repeat(5) { s.list.onEvent(ListEvent.PlusOne(anime.id)) }
            advanceUntilIdle()
            assertEquals(17, s.values(anime)?.watchedEpisodes)
            assertEquals(ShellMessage.Text.EpisodeWatched(17), s.message?.text)
            s.tapAction()
            advanceUntilIdle()
            assertEquals(16, s.values(anime)?.watchedEpisodes)
        }

    @Test
    fun `W3 five quick PlusOne all count and Undo takes back only the last`() = plusOneBurst(lateObservers = false)

    @Test
    fun `W3 five quick PlusOne all count and Undo takes back only the last, with late observers`() = plusOneBurst(lateObservers = true)

    @Test
    fun `W3 a burst that completes the title offers Undo of the completing step only`() =
        test(lateObservers = true) { s ->
            val anime = FixtureAnime.oneShortOfTotal
            val values = s.values(anime)!!
            s.graph.list.write(ListWrite.Save(anime, values.copy(watchedEpisodes = 9)))
            advanceUntilIdle()
            repeat(3) { s.list.onEvent(ListEvent.PlusOne(anime.id)) }
            advanceUntilIdle()
            assertEquals(ShellMessage.Text.MovedToCompleted, s.message?.text)
            s.tapAction()
            advanceUntilIdle()
            assertEquals(values.copy(watchedEpisodes = 11), s.values(anime))
        }

    @Test
    fun `a dismissed message forgets its Undo`() =
        test { s ->
            s.list.onEvent(ListEvent.PlusOne(FixtureAnime.exactToWatch.id))
            advanceUntilIdle()
            val posted = s.messages.current.value!!
            s.messages.ended(posted, actionPerformed = false)
            assertNull(s.message)
            advanceUntilIdle()
            assertEquals(13, s.values(FixtureAnime.exactToWatch)?.watchedEpisodes)
        }

    @Test
    fun `the filter starts empty, filters every tab as typed, keeps the order and ends`() =
        test { s ->
            s.list.onEvent(ListEvent.StartFilter)
            assertEquals("", s.state.filter)
            assertEquals(upNext(), s.ids())
            s.list.onEvent(ListEvent.FilterChanged("kaze"))
            assertEquals("kaze", s.state.filter)
            assertTrue(s.page().rows.isNotEmpty())
            ListStatus.entries.forEach { status ->
                assertTrue(s.page(status).rows.all { it.title.contains("kaze", ignoreCase = true) })
                assertEquals(0, s.page(status).ordering)
            }
            s.list.onEvent(ListEvent.EndFilter)
            assertNull(s.state.filter)
            assertEquals(upNext(), s.ids())
        }

    @Test
    fun `the sort sheet opens, stays open after a choice until dismissed, and a new sort orders only its tab again`() =
        test { s ->
            s.list.onEvent(ListEvent.OpenSort)
            assertTrue(s.state.sortSheetOpen)
            s.list.onEvent(ListEvent.DismissSort)
            assertFalse(s.state.sortSheetOpen)
            s.list.onEvent(ListEvent.OpenSort)
            s.list.onEvent(ListEvent.SelectSort(ListSort.Title))
            assertTrue(s.state.sortSheetOpen)
            assertEquals(ListSort.Title, s.page().sort)
            assertEquals(
                s
                    .page()
                    .rows
                    .map { it.title }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER),
                s.page().rows.map { it.title },
            )
            assertEquals(1, s.page().ordering)
            assertEquals(0, s.page(ListStatus.Planned).ordering)
            s.list.onEvent(ListEvent.SelectSort(ListSort.Title))
            assertEquals(1, s.page().ordering)
            assertTrue(s.state.sortSheetOpen)
            s.list.onEvent(ListEvent.DismissSort)
            assertFalse(s.state.sortSheetOpen)
        }

    @Test
    fun `a direction orders only its tab again from the top, and a new sort starts in its own direction`() =
        test { s ->
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Completed))
            s.list.onEvent(ListEvent.SelectSort(ListSort.Score))
            assertEquals(SortDirection.Descending, s.page().direction)
            val highest = s.ids()
            val planned = s.page(ListStatus.Planned)
            s.list.onEvent(ListEvent.OpenSort)
            s.list.onEvent(ListEvent.SelectDirection(SortDirection.Ascending))
            assertTrue(s.state.sortSheetOpen)
            assertEquals(SortDirection.Ascending, s.page().direction)
            assertEquals(
                order(
                    set.entries.filter { it.status == ListStatus.Completed },
                    ListSort.Score,
                    set.airing::get,
                    SortDirection.Ascending,
                ) {
                    it.anime.titles.romaji
                }.map { it.anime.id },
                s.ids(),
            )
            assertTrue(s.ids() != highest)
            assertSame(planned, s.page(ListStatus.Planned))
            assertEquals(2, s.page().ordering)
            assertEquals(0, s.page(ListStatus.Planned).ordering)
            s.list.onEvent(ListEvent.SelectDirection(SortDirection.Ascending))
            assertTrue(s.state.sortSheetOpen)
            assertEquals(2, s.page().ordering)
            s.list.onEvent(ListEvent.SelectSort(ListSort.Title))
            assertEquals(SortDirection.Ascending, s.page().direction)
            s.list.onEvent(ListEvent.SelectSort(ListSort.LastUpdated))
            assertEquals(SortDirection.Descending, s.page().direction)
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Watching))
            s.list.onEvent(ListEvent.SelectDirection(SortDirection.Ascending))
            assertNull(s.page().direction)
            assertEquals(upNext(), s.ids())
            assertEquals(0, s.page().ordering)
        }

    @Test
    fun `sorted by score or start date, a row shows that value, in the tab sorted so only`() =
        test { s ->
            s.list.onEvent(ListEvent.SelectSort(ListSort.Score))
            val scores = set.entries.filter { it.status == ListStatus.Watching }.associate { it.anime.id to it.values.score }
            assertEquals(s.page().rows.map { r -> scores.getValue(r.id)?.let(SortedBy::Score) }, s.page().rows.map { it.sortedBy })
            assertTrue(s.page().rows.any { it.sortedBy != null })
            assertTrue(s.page(ListStatus.Planned).rows.all { it.sortedBy == null })
            s.list.onEvent(ListEvent.SelectSort(ListSort.StartDate))
            assertTrue(s.page().rows.all { it.sortedBy is SortedBy.StartYear })
            s.list.onEvent(ListEvent.SelectSort(ListSort.Title))
            assertTrue(s.page().rows.all { it.sortedBy == null })
        }

    @Test
    fun `rows do not move after an edit until Refresh orders every tab again`() =
        test { s ->
            s.list.onEvent(ListEvent.SelectSort(ListSort.LastUpdated))
            val before = s.ids()
            val last = before.last()
            s.list.onEvent(ListEvent.PlusOne(last))
            advanceUntilIdle()
            assertEquals(before, s.ids())
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertEquals(last, s.ids().first())
            assertEquals(2, s.page().ordering)
            assertEquals(1, s.page(ListStatus.Planned).ordering)
        }

    @Test
    fun `Refresh shows the indicator while the sync runs`() =
        test(demo = { Demo(Fixtures.reference, it, latency = 100.milliseconds) }) { s ->
            s.list.onEvent(ListEvent.Refresh)
            runCurrent()
            assertTrue(s.state.refreshing)
            advanceTimeBy(500)
            runCurrent()
            assertFalse(s.state.refreshing)
            assertNull(s.message)
        }

    @Test
    fun `a failed Refresh keeps the list and says why`() =
        test { s ->
            s.graph.demo.server.offline.value = true
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertEquals(ShellMessage.Text.RefreshFailed(DataError.NoConnection), s.message?.text)
            assertEquals(upNext(), s.ids())
            assertEquals(0, s.page().ordering)
        }

    @Test
    fun `a sync that fails because the session expired leaves the message to the banner`() =
        test { s ->
            s.graph.demo.server.sessionExpired.value = true
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertTrue(s.state.sessionExpired)
            assertNull(s.message)
        }

    // A title edited on the website moves tabs; Refresh orders the tabs from what the sync brought.
    private fun refreshOrdersSyncedData(lateObservers: Boolean) =
        test(lateObservers = lateObservers) { s ->
            val anime = FixtureAnime.exactToWatch
            val values =
                s.graph.demo.server
                    .entry(anime.id)!!
                    .values
            s.graph.demo.server
                .editOnWebsite(anime, values.copy(status = ListStatus.Completed))
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            val completed =
                s.graph.list
                    .observeEntries()
                    .first()
                    .filter { it.status == ListStatus.Completed }
            val expected = order(completed, ListSort.LastUpdated, set.airing::get) { it.anime.titles.romaji }.map { it.anime.id }
            assertEquals(expected, s.ids(ListStatus.Completed))
            assertEquals(anime.id, s.ids(ListStatus.Completed).first())
        }

    @Test
    fun `Refresh orders every tab from the synced list`() = refreshOrdersSyncedData(lateObservers = false)

    @Test
    fun `Refresh orders every tab from the synced list, with late observers`() = refreshOrdersSyncedData(lateObservers = true)

    // The first sign-in: nothing cached, the whole list on the website.
    private fun firstLoad(scope: CoroutineScope) =
        Demo(set.copy(entries = emptyList()), scope).also { demo -> set.entries.forEach { demo.server.editOnWebsite(it.anime, it.values) } }

    @Test
    fun `the first load into an empty list orders every tab from what the sync brought`() = firstLoadIsOrdered(lateObservers = false)

    @Test
    fun `the first load into an empty list orders every tab from what the sync brought, with late observers`() =
        firstLoadIsOrdered(lateObservers = true)

    // "Up next" is by the airing data the titles got as they became tracked, which arrives after the list.
    private fun firstLoadIsOrdered(lateObservers: Boolean) =
        test(demo = ::firstLoad, lateObservers = lateObservers) { s ->
            val synced =
                s.graph.list
                    .observeEntries()
                    .first()
            ListStatus.entries.forEach { status ->
                val sort = defaultSort(status, withAiring = true)
                val expected =
                    order(
                        synced.filter { it.status == status },
                        sort,
                        set.airing::get,
                    ) { it.anime.titles.romaji }.map { it.anime.id }
                assertTrue(status.name, expected.isNotEmpty())
                assertEquals(status.name, expected, s.ids(status))
            }
        }

    // The store's new list reaches the lane while the sync still waits to order it, as on two threads; it is ordered once, by the sync.
    @Test
    fun `a first load whose list arrives before the sync's own ordering runs orders every tab once`() {
        val lane = SyncLast(dispatcher)
        test(demo = ::firstLoad, background = lane) { s ->
            assertEquals(1, lane.deferred)
            assertEquals(ListContent.Loading, s.state.content)
            lane.release()
            advanceUntilIdle()
            ListStatus.entries.forEach { assertEquals(it.name, 0, s.page(it).ordering) }
            assertTrue(s.ids(ListStatus.Watching).isNotEmpty())
        }
    }

    // Every state the List passes through, also those a later one replaces at once, from before it is first shown.
    private fun TestScope.recorded(list: ListViewModel): List<ListState> {
        val states = mutableListOf<ListState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { list.state.collect { states += it } }
        return states
    }

    private fun listOver(
        graph: TestGraph,
        scope: CoroutineScope,
    ) = ListViewModel(
        graph.list,
        graph.anime,
        graph.airing,
        graph.session,
        graph.connection,
        graph.preferences,
        graph.clock,
        graph.titleLanguage,
        SavedStateHandle(),
        ShellMessages(scope, graph.list),
        graph.background,
    )

    // With no airing data stored, "Up next" by estimates differs from the order by the fixtures' schedules.
    private fun startsWithNoAiringStored(downloaded: Boolean) =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val demo = if (downloaded) firstLoad(scope) else Demo(set, scope, airingStored = false)
            val graph = TestGraph(demo)
            if (downloaded) {
                // A first download that reached the store before the List first reads it, its airing data still to come.
                demo.airingSource.failure = DataError.NoConnection
                graph.list.sync()
                advanceUntilIdle()
                demo.airingSource.failure = null
            }
            val watching =
                graph.list
                    .observeEntries()
                    .first()
                    .filter { it.status == ListStatus.Watching }
            val estimates = watching.associate { it.anime.id to airingAt(it.anime, null, set.now) }
            assertNotEquals(
                order(watching, ListSort.UpNext, estimates::get) {
                    it.anime.titles.romaji
                },
                order(watching, ListSort.UpNext, set.airing::get) { it.anime.titles.romaji },
            )
            val expected = order(watching, ListSort.UpNext, set.airing::get) { it.anime.titles.romaji }.map { it.anime.id }
            val list = listOver(graph, scope)
            val states = recorded(list)
            showing(list.state, list.shown)
            advanceUntilIdle()
            val shown = states.mapNotNull { (it.content as? ListContent.Tabs)?.pages?.getValue(ListStatus.Watching) }
            assertTrue(shown.isNotEmpty())
            shown.forEach { assertEquals(expected, it.rows.map { row -> row.id }) }
            assertEquals(0, shown.last().ordering)
            assertTrue(demo.airingSource.requests.isNotEmpty())
            list.viewModelScope.cancel()
            scope.cancel()
        }

    @Test
    fun `a start with no airing data stored shows Watching only in the order the airing refresh gives it`() =
        startsWithNoAiringStored(downloaded = false)

    @Test
    fun `a first download already stored when the List first reads it shows Watching only in the order its airing data gives it`() =
        startsWithNoAiringStored(downloaded = true)

    // AniList fails meanwhile, so no airing data the engine asks for by itself is stored either.
    @Test
    fun `a start whose airing refresh never ends orders its stored list once the wait is over`() =
        test(
            demo = { Demo(set, it, airingStored = false).apply { airingSource.failure = DataError.NoConnection } },
            airing = { inner ->
                object : AiringRepository by inner {
                    override suspend fun refreshStale(): Outcome<Unit> = awaitCancellation()
                }
            },
        ) { s ->
            assertEquals(set.entries.count { it.status == ListStatus.Watching }, s.ids(ListStatus.Watching).size)
            assertEquals(0, s.page().ordering)
        }

    // The next episode of [anime] moved to an hour ago, as AniList can move it.
    private fun movedSchedules(anime: Anime): Map<AnimeId, AiringSchedule> {
        val schedule = set.schedules.getValue(anime.id)
        val next = checkNotNull(set.airing.getValue(anime.id).nextEpisode).number
        val moved =
            schedule.copy(
                episodes =
                    schedule.episodes.map {
                        if (it.number ==
                            next
                        ) {
                            it.copy(airsAt = set.now.minusSeconds(HOUR))
                        } else {
                            it
                        }
                    },
            )
        return set.schedules + (anime.id to moved)
    }

    @Test
    fun `a pull asks for every tracked title's airing and orders by what that brought, also when it ends after the sync`() =
        test { s ->
            val source = s.graph.demo.airingSource
            val asked = source.requests.size
            val moved = movedSchedules(FixtureAnime.exactCaughtUp)
            source.schedules = moved
            val answer = CompletableDeferred<Unit>()
            source.holding = answer
            val syncs = s.graph.demo.server.listDownloads
            s.list.onEvent(ListEvent.Refresh)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(syncs + 1, s.graph.demo.server.listDownloads)
            assertTrue(s.state.refreshing)
            assertEquals(upNext(), s.ids())
            answer.complete(Unit)
            advanceUntilIdle()
            val airing = set.copy(schedules = moved).airing
            val expected =
                order(
                    set.entries.filter {
                        it.status == ListStatus.Watching
                    },
                    ListSort.UpNext,
                    airing::get,
                ) { it.anime.titles.romaji }.map { it.anime.id }
            assertNotEquals(upNext(), expected)
            assertEquals(expected, s.ids())
            assertEquals(1, s.page().ordering)
            assertEquals(
                set.entries
                    .filter { it.tracked }
                    .map { it.anime.id }
                    .toSet(),
                source.requests
                    .drop(asked)
                    .flatten()
                    .toSet(),
            )
            assertNull(s.message)
        }

    @Test
    fun `a pull orders also when AniList fails, and nothing says so`() =
        test { s ->
            s.graph.demo.airingSource.failure = DataError.ServerError
            val asked = s.graph.demo.airingSource.requests.size
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertTrue(s.graph.demo.airingSource.requests.size > asked)
            assertEquals(upNext(), s.ids())
            assertEquals(1, s.page().ordering)
            assertFalse(s.state.refreshing)
            assertNull(s.message)
        }

    @Test
    fun `a pull whose airing refresh outlasts the wait orders without it and ends`() =
        test(airing = { inner ->
            object : AiringRepository by inner {
                override suspend fun refresh(): Outcome<Unit> = awaitCancellation()
            }
        }) { s ->
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertEquals(1, s.page().ordering)
            assertFalse(s.state.refreshing)
        }

    // Shown again once its list and its airing are stale, the List asks for the tracked titles' airing though MAL fails.
    private fun staleAiringIsAskedAfterAFailedSync(
        offline: Boolean = false,
        fail: (Demo) -> Unit,
    ) {
        val clock = MovingClock(set.now, FixtureTime.zone)
        test(demo = { Demo(set, it, clock = clock) }) { s ->
            val source = s.graph.demo.airingSource
            val asked = source.requests.size
            s.screen.cancel()
            runCurrent()
            advanceTimeBy(ROTATION + STOPPED_AFTER)
            runCurrent()
            fail(s.graph.demo)
            clock.instant = set.now.plus(Duration.ofMinutes(20))
            val syncs = s.graph.syncs
            s.screen = showing(s.list.state, s.list.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(syncs + 1, s.graph.syncs)
            val tracked =
                set.entries
                    .filter { it.tracked }
                    .map { it.anime.id }
                    .toSet()
            if (offline) {
                // Nothing is asked offline; the sync repeated once the connection is back asks after its success.
                assertEquals(emptyList<Set<AnimeId>>(), source.requests.drop(asked))
                s.graph.demo.server.offline.value = false
                advanceUntilIdle()
                assertEquals(syncs + 2, s.graph.syncs)
            }
            assertEquals(listOf(tracked), source.requests.drop(asked))
        }
    }

    @Test
    fun `with the session expired the List shown asks for its stale airing`() =
        staleAiringIsAskedAfterAFailedSync { it.server.sessionExpired.value = true }

    @Test
    fun `offline the List shown asks nothing of AniList, and asks for its stale airing once the connection is back`() =
        staleAiringIsAskedAfterAFailedSync(offline = true) { it.server.offline.value = true }

    // N2's bound: the pull ends as MAL's failure says, whatever AniList does with the ask that follows it.
    @Test
    fun `a pull that MAL fails ends and says so without waiting for the airing it asks for`() {
        val clock = MovingClock(set.now, FixtureTime.zone)
        test(demo = { Demo(set, it, clock = clock) }) { s ->
            val source = s.graph.demo.airingSource
            val asked = source.requests.size
            val answer = CompletableDeferred<Unit>()
            source.holding = answer
            s.graph.demo.server.failingLoads.value = true
            clock.instant = set.now.plus(Duration.ofMinutes(20))
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertFalse(s.state.refreshing)
            assertEquals(ShellMessage.Text.RefreshFailed(DataError.ServerError), s.message?.text)
            assertTrue(source.requests.size > asked)
            answer.complete(Unit)
            advanceUntilIdle()
        }
    }

    @Test
    fun `with MAL failing the List shown asks for its stale airing`() =
        staleAiringIsAskedAfterAFailedSync {
            it.server.failingLoads.value =
                true
        }

    // Section 6.2: the airing of the rows is a part of them; the list stays, and a pull reads it again.
    @Test
    fun `an airing read that fails alone shows the list without its airing and no message, and a pull reads it again`() {
        val failing = MutableStateFlow(false)
        test(airing = { inner ->
            object : AiringRepository by inner {
                override fun observeAiring() =
                    combine(inner.observeAiring(), failing) { airing, failing ->
                        if (failing) throw DataException(DataError.StorageError)
                        airing
                    }
            }
        }) { s ->
            val anime = FixtureAnime.exactToWatch
            val aired = Line3.ToWatch(AiredCount.Exact(2))
            val line3 = {
                s
                    .page()
                    .rows
                    .single { it.id == anime.id }
                    .line3
            }
            val ids = s.ids()
            assertEquals(aired, line3())
            failing.value = true
            advanceUntilIdle()
            assertNotEquals(aired, line3())
            assertEquals(ids, s.ids())
            assertNull(s.message)
            failing.value = false
            advanceUntilIdle()
            assertNotEquals(aired, line3())
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertEquals(aired, line3())
            assertNull(s.message)
            // Followed again, not only read once by the pull's order.
            s.graph.demo.airingSource.schedules = movedSchedules(anime)
            s.graph.airing.refresh()
            advanceUntilIdle()
            assertEquals(Line3.ToWatch(AiredCount.Exact(3)), line3())
            // A pull whose own order cannot read the airing orders the rows without it.
            failing.value = true
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertNotEquals(Line3.ToWatch(AiredCount.Exact(3)), line3())
            assertEquals(2, s.page().ordering)
            assertNull(s.message)
        }
    }

    @Test
    fun `a start with no airing stored whose airing cannot be read orders its rows without it, and says nothing`() =
        test(
            demo = { Demo(set, it, airingStored = false) },
            airing = { inner ->
                object : AiringRepository by inner {
                    override fun observeAiring(): Flow<Map<AnimeId, Airing>> = flow { throw DataException(DataError.StorageError) }
                }
            },
        ) { s ->
            assertEquals(set.entries.count { it.status == ListStatus.Watching }, s.ids(ListStatus.Watching).size)
            assertEquals(0, s.page().ordering)
            assertNull(s.message)
        }

    // The rows of the shown tab after [change], which must leave every row but [changed] the same object, in its place.
    private suspend fun TestScope.inPlace(
        s: Subject,
        changed: AnimeId,
        change: suspend () -> Unit,
    ): ListRow {
        val ids = s.ids()
        val before = s.page().rows
        change()
        advanceUntilIdle()
        assertEquals(ids, s.ids().take(ids.size))
        assertEquals(0, s.page().ordering)
        for ((old, new) in before.zip(s.page().rows)) {
            if (new.id != changed) assertSame(new.title, old, new)
        }
        return s.page().rows.single { it.id == changed }
    }

    @Test
    fun `a refresh that moves an episode updates its row in place, though the order would now differ`() =
        test { s ->
            val anime = FixtureAnime.exactCaughtUp
            val moved = movedSchedules(anime)
            val sorted =
                order(set.entries.filter { it.status == ListStatus.Watching }, ListSort.UpNext, set.copy(schedules = moved).airing::get) {
                    it.anime.titles.romaji
                }.map { it.anime.id }
            assertNotEquals(s.ids(), sorted)
            s.graph.demo.airingSource.schedules = moved
            val row = inPlace(s, anime.id) { assertEquals(Outcome.Success(Unit), s.graph.airing.refresh()) }
            assertEquals(Line3.ToWatch(AiredCount.Exact(1)), row.line3)
        }

    // On a device most starts find their airing data stale; the order is not held for a refresh then.
    @Test
    fun `a start with airing data stored orders at once, whatever its airing refresh does`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(set, scope))
            val airing =
                object : AiringRepository by graph.airing {
                    override suspend fun refreshStale(): Outcome<Unit> = awaitCancellation()
                }
            val list =
                ListViewModel(
                    graph.list,
                    graph.anime,
                    airing,
                    graph.session,
                    graph.connection,
                    graph.preferences,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    ShellMessages(scope, graph.list),
                    graph.background,
                )
            val screen = showing(list.state, list.shown)
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(
                upNext(),
                (list.state.value.content as ListContent.Tabs)
                    .pages
                    .getValue(ListStatus.Watching)
                    .rows
                    .map { it.id },
            )
            screen.cancel()
            list.viewModelScope.cancel()
            scope.cancel()
        }

    // The List's read fails while the sync, which reads the store through the engine, goes on; a Retry then reads it again.
    @Test
    fun `Retry of an unreadable list during its first sync, with no airing stored, waits for the airing refresh alone`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph(Demo(set, scope, latency = 20.seconds, airingStored = false))
            val failing = MutableStateFlow(true)
            val entries =
                object : ListSource by graph.list {
                    override fun observeEntries() =
                        combine(graph.list.observeEntries(), failing) { entries, failing ->
                            if (failing) throw DataException(DataError.StorageError)
                            entries
                        }
                }
            val list =
                ListViewModel(
                    entries,
                    graph.anime,
                    graph.airing,
                    graph.session,
                    graph.connection,
                    graph.preferences,
                    graph.clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    ShellMessages(scope, graph.list),
                    graph.background,
                )
            val screen = showing(list.state, list.shown)
            advanceTimeBy(6.seconds)
            runCurrent()
            assertEquals(ListContent.Failed(ListFailure.Other), list.state.value.content)
            failing.value = false
            list.onEvent(ListEvent.Retry)
            advanceTimeBy(7.seconds)
            runCurrent()
            assertEquals(set.entries.size, (list.state.value.content as ListContent.Tabs).pages.values.sumOf { it.rows.size })
            advanceUntilIdle()
            screen.cancel()
            list.viewModelScope.cancel()
            scope.cancel()
        }

    @Test
    fun `a title that becomes tracked joins the end of its tab, and its airing data arrives in its row in place`() =
        test { s ->
            val anime = FixtureAnime.notOnListAiring
            val asked = s.graph.demo.airingSource.requests.size
            val row =
                inPlace(s, anime.id) {
                    s.graph.list.write(
                        ListWrite.Save(anime, EditSheetState.newEntry.copy(status = ListStatus.Watching, watchedEpisodes = 1)),
                    )
                }
            assertEquals(anime.id, s.ids().last())
            assertEquals(
                listOf(setOf(anime.id)),
                s.graph.demo.airingSource.requests
                    .drop(asked),
            )
            assertEquals(Line3.ToWatch(AiredCount.Exact(6)), row.line3)
        }

    @Test
    fun `Retry syncs the list again`() =
        test { s ->
            val downloads = s.graph.demo.server.listDownloads
            s.list.onEvent(ListEvent.Retry)
            advanceUntilIdle()
            assertEquals(downloads + 1, s.graph.demo.server.listDownloads)
        }

    @Test
    fun `a title that joins a tab goes at its end`() =
        test { s ->
            val anime = FixtureAnime.plannedAiring
            s.graph.list.write(ListWrite.Save(anime, s.values(anime)!!.copy(status = ListStatus.Watching, watchedEpisodes = 1)))
            advanceUntilIdle()
            assertEquals(upNext() + anime.id, s.ids())
        }

    private val unknown = ListFixtureStates.unknownStatus
    private val withUnknown = ListFixtureStates.withUnknownStatus

    @Test
    fun `entries whose status the app does not know have a last tab of their own, which keeps no sort, until no entry has it`() {
        val preferences = DemoPreferences()
        val saved = SavedStateHandle()
        test(demo = { Demo(set.copy(entries = withUnknown), it) }, saved = saved, preferences = preferences) { s ->
            assertEquals(ListStatus.entries + unknown, (s.state.content as ListContent.Tabs).statuses)
            val shown = withUnknown.filter { it.status == unknown }
            val expected = order(shown, ListSort.LastUpdated, set.airing::get) { it.anime.titles.romaji }.map { it.anime.id }
            assertEquals(2, expected.size)
            assertEquals(expected, s.ids(unknown))
            assertEquals(ListSort.LastUpdated, s.page(unknown).sort)
            assertFalse(ListSort.UpNext in s.page(unknown).sorts)
            assertTrue(s.page(unknown).rows.none { it.plusOne })

            s.list.onEvent(ListEvent.SelectTab(unknown))
            s.list.onEvent(ListEvent.SelectSort(ListSort.Title))
            advanceUntilIdle()
            assertEquals(unknown, s.state.tab)
            assertEquals(ListSort.Title, s.page().sort)
            assertEquals(ListStatus.entries.associateWith(TabSort::defaultFor), preferences.observeListSorts().first())

            for (entry in shown) s.graph.list.write(ListWrite.Save(entry.anime, entry.values.copy(status = ListStatus.Dropped)))
            advanceUntilIdle()
            assertEquals(unknown, s.state.tab)
            assertEquals(emptyList<AnimeId>(), s.ids())

            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertEquals(ListStatus.entries, (s.state.content as ListContent.Tabs).statuses)
            assertEquals(ListStatus.Dropped, s.state.tab)
            assertTrue(s.ids().containsAll(expected))
        }
        test(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })) { s ->
            assertEquals(ListStatus.Dropped, s.state.tab)
        }
    }

    @Test
    fun `a status the app does not know that a sync brings opens its tab at once, and the tab survives process death`() {
        val saved = SavedStateHandle()
        val anime = FixtureAnime.onHoldAiring
        test(saved = saved) { s ->
            val values =
                s.graph.demo.server
                    .entry(anime.id)!!
                    .values
            s.graph.demo.server
                .editOnWebsite(anime, values.copy(status = unknown))
            s.list.onEvent(ListEvent.Retry)
            advanceUntilIdle()
            assertEquals(ListStatus.entries + unknown, (s.state.content as ListContent.Tabs).statuses)
            assertEquals(listOf(anime.id), s.ids(unknown))
            assertFalse(anime.id in s.ids(ListStatus.OnHold))
            ListStatus.entries.forEach { assertEquals(it.name, 0, s.page(it).ordering) }
            s.list.onEvent(ListEvent.SelectTab(unknown))
            advanceUntilIdle()
        }
        test(demo = {
            Demo(set.copy(entries = withUnknown), it)
        }, saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })) { s ->
            assertEquals(unknown, s.state.tab)
        }
    }

    @Test
    fun `the events the route handles change nothing here`() =
        test { s ->
            val before = s.state
            listOf(
                ListEvent.Open(FixtureAnime.exactToWatch.id),
                ListEvent.Edit(FixtureAnime.exactToWatch.id),
                ListEvent.Search,
                ListEvent.SignIn,
                ListEvent.Back,
            ).forEach(s.list::onEvent)
            advanceUntilIdle()
            assertEquals(before.copy(now = s.state.now), s.state)
        }

    @Test
    fun `an expired session shows the banner over the list, and signing out shows the signed-out state`() =
        test { s ->
            s.graph.demo.server.sessionExpired.value = true
            advanceUntilIdle()
            assertTrue(s.state.sessionExpired)
            assertTrue(s.state.content is ListContent.Tabs)
            s.graph.session.signOut()
            advanceUntilIdle()
            assertEquals(ListContent.SignedOut, s.state.content)
        }

    @Test
    fun `the list is read only while the List is shown, and shows what changed meanwhile when it is shown again`() =
        test { s ->
            assertTrue(s.graph.readers > 0)
            s.screen.cancel()
            advanceTimeBy(ROTATION)
            runCurrent()
            assertTrue(s.graph.readers > 0)
            advanceTimeBy(STOPPED_AFTER)
            runCurrent()
            assertEquals(0, s.graph.readers)
            s.graph.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            assertEquals(0, s.graph.readers)
            s.screen = showing(s.list.state, s.list.shown)
            runCurrent()
            assertTrue(s.graph.readers > 0)
            val row = s.page().rows.single { it.id == FixtureAnime.exactToWatch.id }
            assertEquals(13, (row.line2 as Line2.Progress).watched)
        }

    @Test
    fun `the first sync runs once, not again when the List is shown again`() =
        test { s ->
            val downloads = s.graph.demo.server.listDownloads
            s.screen.cancel()
            advanceTimeBy(ROTATION + STOPPED_AFTER)
            s.screen = showing(s.list.state, s.list.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(downloads, s.graph.demo.server.listDownloads)
        }

    @Test
    fun `shown with a list that needs no sync, the List asks for the airing data that went stale, and for nothing while it is fresh`() {
        val clock = MovingClock(set.now.minus(Duration.ofMinutes(10)), FixtureTime.zone)
        test(demo = { Demo(set, it, clock = clock).also { clock.instant = set.now } }) { s ->
            val requests = { s.graph.demo.airingSource.requests }
            val downloads = s.graph.demo.server.listDownloads
            assertEquals(emptyList<Set<AnimeId>>(), requests())

            for ((minutes, asked) in listOf(4L to 0, 6L to 1, 7L to 1)) {
                s.screen.cancel()
                runCurrent()
                advanceTimeBy(ROTATION + STOPPED_AFTER)
                runCurrent()
                clock.instant = set.now.plus(Duration.ofMinutes(minutes))
                s.screen = showing(s.list.state, s.list.shown)
                runCurrent()
                advanceUntilIdle()
                assertEquals("after $minutes minutes", asked, requests().size)
            }
            assertEquals(
                set.entries
                    .filter { it.tracked }
                    .map { it.anime.id }
                    .toSet(),
                requests().single(),
            )
            assertEquals(downloads, s.graph.demo.server.listDownloads)
        }
    }

    // Nothing asynchronous sits between a keystroke and the field: no dispatch, no frame.
    @Test
    fun `the filter field's text changes with each keystroke before anything runs`() =
        test { s ->
            s.list.onEvent(ListEvent.StartFilter)
            "kag".forEachIndexed { i, _ ->
                val text = "kag".take(i + 1)
                s.list.onEvent(ListEvent.FilterChanged(text))
                assertEquals(text, s.list.filter)
            }
            s.list.onEvent(ListEvent.EndFilter)
            assertNull(s.list.filter)
        }

    @Test
    fun `the pages are built once while what they show, the day and the air times they show stay the same`() {
        val clock = MovingClock(set.now, FixtureTime.zone)
        test(demo = { Demo(set, it, clock = clock) }) { s ->
            val first = s.state
            clock.instant = minOf(nextAirTime, midnight).minusSeconds(1)
            for (event in listOf(ListEvent.SelectTab(ListStatus.Planned), ListEvent.OpenSort, ListEvent.DismissSort)) {
                s.list.onEvent(event)
                assertSame("$event", first.content, s.state.content)
                assertEquals("$event", first.now, s.state.now)
            }
        }
    }

    @Test
    fun `the pages are built again once the day ends`() {
        val clock = MovingClock(midnight.minusSeconds(1), FixtureTime.zone)
        test(demo = { Demo(set, it, clock = clock) }) { s ->
            val before = s.state
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Planned))
            assertSame(before.content, s.state.content)
            clock.instant = midnight
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Watching))
            assertNotSame(before.content, s.state.content)
            assertEquals(clock.instant, s.state.now)
        }
    }

    // At the end of a premiere's day in Japan only that row changes; every other row is the same object.
    @Test
    fun `at the end of a premiere's day in Japan its row alone changes, by the tick`() {
        val anime = FixtureAnime.premiereDate
        val premiere = (anime.startDate as PartialDate.Day).date
        val end = premiere.plusDays(1).atStartOfDay(FixtureTime.japan).toInstant()
        val clock = MovingClock(end.minusSeconds(1), FixtureTime.zone)
        val clockSet = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        test(demo = { Demo(set, it, clock = clock, clockSet = clockSet) }) { s ->
            val before = ListStatus.entries.associateWith { s.page(it).rows }
            assertEquals(
                Line2.Premieres(premiereOn(PartialDate.Day(premiere))),
                before
                    .getValue(ListStatus.Planned)
                    .single {
                        it.id ==
                            anime.id
                    }.line2,
            )

            clock.instant = end
            clockSet.emit(Unit)
            advanceUntilIdle()

            assertEquals(end, s.state.now)
            for (status in ListStatus.entries) {
                val rows = before.getValue(status).zip(s.page(status).rows)
                assertEquals(status.name, before.getValue(status).size, rows.size)
                rows.forEach { (old, new) ->
                    if (new.id ==
                        anime.id
                    ) {
                        assertEquals(Line2.Premieres(Premiere.Unannounced), new.line2)
                    } else {
                        assertSame(new.title, old, new)
                    }
                }
            }
        }
    }

    @Test
    fun `an episode airing updates its row in place, and every other row stays the same object`() {
        val schedule = MutableStateFlow(set.airing)
        test(airing = { inner ->
            object : AiringRepository by inner {
                override fun observeAiring(): Flow<Map<AnimeId, Airing>> = schedule
            }
        }) { s ->
            val anime = FixtureAnime.exactToWatch
            val ids = s.ids()
            val before = s.page().rows
            assertEquals(Line3.ToWatch(AiredCount.Exact(2)), before.single { it.id == anime.id }.line3)
            val old = set.airing.getValue(anime.id)
            val aired = old.nextEpisode!!
            schedule.value = set.airing + (anime.id to airingAt(anime, set.schedules.getValue(anime.id), aired.airsAt))
            advanceUntilIdle()
            assertEquals(ids, s.ids())
            assertEquals(0, s.page().ordering)
            for ((old, new) in before.zip(s.page().rows)) {
                if (new.id == anime.id) assertEquals(Line3.ToWatch(AiredCount.Exact(3)), new.line3) else assertSame(new.title, old, new)
            }
        }
    }

    @Test
    fun `after a PlusOne every other row and every other tab's page is the same object`() =
        test { s ->
            val anime = FixtureAnime.exactToWatch
            val before = (s.state.content as ListContent.Tabs).pages
            s.list.onEvent(ListEvent.PlusOne(anime.id))
            advanceUntilIdle()
            val after = (s.state.content as ListContent.Tabs).pages
            for (status in ListStatus.entries - ListStatus.Watching) assertSame(status.name, before[status], after[status])
            val rows = before.getValue(ListStatus.Watching).rows.zip(after.getValue(ListStatus.Watching).rows)
            assertEquals(set.entries.count { it.status == ListStatus.Watching }, rows.size)
            for ((old, new) in rows) {
                assertEquals(old.id, new.id)
                if (new.id == anime.id) assertNotEquals(old, new) else assertSame(new.title, old, new)
            }
        }

    // The clock is read for every page built and every order computed, so the threads it is read on are theirs.
    @Test
    fun `no state is built and no order computed on the main thread`() =
        runTest(dispatcher) {
            val main = Thread.currentThread()
            val pool = Executors.newSingleThreadExecutor()
            val background = pool.asCoroutineDispatcher()
            val threads = CopyOnWriteArraySet<Thread>()
            val clock =
                object : Clock() {
                    override fun instant(): Instant {
                        val building =
                            Throwable().stackTrace.any {
                                it.className.startsWith(ListViewModel::class.java.name) &&
                                    it.methodName in STATE_WORK
                            }
                        if (building) threads += Thread.currentThread()
                        return set.now
                    }

                    override fun getZone(): ZoneId = FixtureTime.zone

                    override fun withZone(zone: ZoneId): Clock = this
                }
            val scope = foregroundScope()
            val graph = TestGraph(Demo(set, scope, clock = clock))
            val list =
                ListViewModel(
                    graph.list,
                    graph.anime,
                    graph.airing,
                    graph.session,
                    graph.connection,
                    graph.preferences,
                    clock,
                    graph.titleLanguage,
                    SavedStateHandle(),
                    ShellMessages(scope, graph.list),
                    background,
                )
            val screen = showing(list.state, list.shown)

            suspend fun until(condition: (ListState) -> Boolean) =
                withContext(Dispatchers.Default) { withTimeout(TIMEOUT) { list.state.first(condition) } }

            until { it.content is ListContent.Tabs }
            list.onEvent(ListEvent.PlusOne(FixtureAnime.exactToWatch.id))
            until { state -> state.page!!.rows.any { (it.line2 as? Line2.Progress)?.watched == 13 } }
            list.onEvent(ListEvent.SelectSort(ListSort.Title))
            until { it.page?.sort == ListSort.Title }
            list.onEvent(ListEvent.Refresh)
            until { it.page?.ordering == 2 }

            assertEquals(setOf(pool.submit<Thread> { Thread.currentThread() }.get()), threads.toSet())
            assertFalse(main in threads)
            screen.cancel()
            list.viewModelScope.cancel()
            scope.cancel()
            pool.shutdown()
        }

    @Test
    fun `an air time that passes moves its row by the tick, with no event, and the moment the pages are written against stays`() {
        val anime = FixtureAnime.exactToWatch
        val airsAt = checkNotNull(set.airing.getValue(anime.id).nextEpisode).airsAt
        val clock = MovingClock(airsAt.minusSeconds(1), FixtureTime.zone)
        val clockSet = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        test(demo = { Demo(set, it, clock = clock, clockSet = clockSet) }) { s ->
            val before = s.state
            assertEquals(
                Line3.ToWatch(AiredCount.Exact(2)),
                s
                    .page()
                    .rows
                    .single { it.id == anime.id }
                    .line3,
            )

            clock.instant = airsAt
            clockSet.emit(Unit)
            advanceUntilIdle()

            assertEquals(
                Line3.ToWatch(AiredCount.Exact(3)),
                s
                    .page()
                    .rows
                    .single { it.id == anime.id }
                    .line3,
            )
            assertEquals(before.now, s.state.now)
            assertEquals(
                s.ids(),
                (before.content as ListContent.Tabs)
                    .pages
                    .getValue(ListStatus.Watching)
                    .rows
                    .map { it.id },
            )
        }
    }

    @Test
    fun `a first load orders its tabs though the airing refresh that follows the sync never ends`() =
        test(
            demo = ::firstLoad,
            airing = { inner ->
                object : AiringRepository by inner {
                    override suspend fun refreshStale(): Outcome<Unit> = awaitCancellation()
                }
            },
        ) { s ->
            assertEquals(set.entries.count { it.status == ListStatus.Watching }, s.ids(ListStatus.Watching).size)
            assertFalse(s.state.refreshing)
        }

    @Test
    fun `the pages are built again when the clock is set back`() {
        val clock = MovingClock(midnight.plusSeconds(HOUR), FixtureTime.zone)
        test(demo = { Demo(set, it, clock = clock) }) { s ->
            val ahead = s.state
            clock.instant = set.now
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Planned))
            assertNotSame(ahead.content, s.state.content)
            assertEquals(set.now, s.state.now)
        }
    }

    @Test
    fun `the pages are built again when the time zone changes`() {
        val clock = MovingClock(set.now, FixtureTime.zone)
        test(demo = { Demo(set, it, clock = clock) }) { s ->
            val before = s.state
            clock.moveTo(ZoneOffset.ofHours(-12))
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Planned))
            assertNotSame(before.content, s.state.content)
        }
    }

    @Test
    fun `a Refresh that brings nothing builds the pages again`() {
        val clock = MovingClock(set.now, FixtureTime.zone)
        test(demo = { Demo(set, it, clock = clock) }) { s ->
            val before = s.state
            clock.instant = clock.instant.plusSeconds(1)
            s.graph.demo.server.offline.value = true
            s.list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertNotSame(before.content, s.state.content)
            assertEquals(clock.instant, s.state.now)
        }
    }

    @Test
    fun `nothing is read or synced before the List is first shown`() =
        runTest(dispatcher) {
            val scope = foregroundScope()
            val graph = TestGraph.over(scope)
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
                    ShellMessages(scope, graph.list),
                    graph.background,
                )
            val downloads = graph.demo.server.listDownloads
            advanceUntilIdle()
            assertEquals(0, graph.readers)
            assertEquals(downloads, graph.demo.server.listDownloads)
            assertEquals(ListContent.Loading, list.state.value.content)
            val screen = showing(list.state, list.shown)
            runCurrent()
            assertTrue(graph.readers > 0)
            advanceUntilIdle()
            assertEquals(downloads + 1, graph.demo.server.listDownloads)
            screen.cancel()
            scope.cancel()
        }

    @Test
    fun `a list synced moments ago is shown from the store with no sync, an empty one too, also when drawn before shown`() =
        runTest(dispatcher) {
            for (entries in listOf(set.entries, emptyList())) {
                val scope = foregroundScope()
                val graph = TestGraph(Demo(set.copy(entries = entries), scope))
                graph.list.sync()
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
                        ShellMessages(scope, graph.list),
                        graph.background,
                    )
                // Drawn first, as under a back gesture, then shown: the stored list is read before it is known fresh.
                val drawn = showing(list.state)
                runCurrent()
                advanceUntilIdle()
                val screen = showing(list.shown)
                runCurrent()
                advanceUntilIdle()
                assertEquals(1, graph.syncs)
                val pages = (list.state.value.content as ListContent.Tabs).pages
                assertEquals(entries.size, pages.values.sumOf { it.rows.size })
                drawn.cancel()
                screen.cancel()
                list.viewModelScope.cancel()
                scope.cancel()
            }
        }

    // On a pool of threads, the first choice's work starts last: one step at a time and in order, the last choice wins.
    @Test
    fun `quick choices on a pool of threads end on the last, on the screen and in the store`() {
        val pool = SlowFirst()
        test(background = pool) { s ->
            suspend fun until(condition: (ListState) -> Boolean) =
                withContext(Dispatchers.Default) { withTimeout(TIMEOUT) { s.list.state.first(condition) } }
            until { it.content is ListContent.Tabs }
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Completed))
            s.list.onEvent(ListEvent.SelectSort(ListSort.Title))
            s.list.onEvent(ListEvent.SelectDirection(SortDirection.Descending))
            until { it.page?.sort == ListSort.Title && it.page?.direction == SortDirection.Descending }

            pool.slowNext.set(true)
            s.list.onEvent(ListEvent.SelectDirection(SortDirection.Ascending))
            s.list.onEvent(ListEvent.SelectDirection(SortDirection.Descending))
            withContext(Dispatchers.Default) { withTimeout(TIMEOUT) { pool.idle() } }
            until { it.page?.ordering == 4 || it.page?.direction == SortDirection.Ascending }

            assertEquals(
                SortDirection.Descending,
                s.list.state.value.page
                    ?.direction,
            )
            assertEquals(
                4,
                s.list.state.value.page
                    ?.ordering,
            )
            assertEquals(
                TabSort(ListSort.Title, SortDirection.Descending),
                s.graph.preferences
                    .observeListSorts()
                    .first()
                    .getValue(ListStatus.Completed),
            )
            s.list.viewModelScope.cancel()
        }
        pool.shutdown()
    }

    @Test
    fun `a new process over the same store shows each tab's chosen sort, and the tab and the filter survive process death`() {
        val saved = SavedStateHandle()
        val store = DemoPreferences()
        test(saved = saved, preferences = store) { s ->
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Completed))
            s.list.onEvent(ListEvent.SelectSort(ListSort.Score))
            s.list.onEvent(ListEvent.SelectDirection(SortDirection.Ascending))
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Planned))
            s.list.onEvent(ListEvent.SelectSort(ListSort.Title))
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Completed))
            s.list.onEvent(ListEvent.StartFilter)
            s.list.onEvent(ListEvent.FilterChanged("a"))
            advanceUntilIdle()
        }
        test(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }), preferences = store) { s ->
            assertEquals(ListStatus.Completed, s.state.tab)
            assertEquals("a", s.list.filter)
            assertEquals("a", s.state.filter)
        }
        test(preferences = store) { s ->
            assertEquals(ListStatus.Watching, s.state.tab)
            assertNull(s.list.filter)
            val sorts = ListStatus.entries.associateWith { s.page(it).sort to s.page(it).direction }
            val expected =
                ListStatus.entries.associateWith { TabSort.defaultFor(it).sort to TabSort.defaultFor(it).direction } +
                    mapOf(
                        ListStatus.Completed to (ListSort.Score to SortDirection.Ascending),
                        ListStatus.Planned to (ListSort.Title to SortDirection.Ascending),
                    )
            assertEquals(expected, sorts)
            assertEquals(
                order(
                    set.entries.filter { it.status == ListStatus.Completed },
                    ListSort.Score,
                    set.airing::get,
                    SortDirection.Ascending,
                ) {
                    it.anime.titles.romaji
                }.map { it.anime.id },
                s.ids(ListStatus.Completed),
            )
            ListStatus.entries.forEach { assertEquals(it.name, 0, s.page(it).ordering) }
        }
    }

    private companion object {
        const val ROTATION = 4_000L
        const val STOPPED_AFTER = 1_001L
        val TIMEOUT = 10.seconds
        val STATE_WORK = setOf("build", "ordered")
    }
}

private const val HOUR = 3_600L

// Where a sync's own ordering runs.
private val SYNC_ORDERS = setOf("sync", "ordered")

// The lane on one thread, on which the sync's own work waits until [release] and everything else runs as it comes.
private class SyncLast(
    private val inner: CoroutineDispatcher,
) : CoroutineDispatcher() {
    private val queue = mutableListOf<Pair<CoroutineContext, Runnable>>()
    val deferred: Int get() = queue.size

    override fun limitedParallelism(
        parallelism: Int,
        name: String?,
    ): CoroutineDispatcher = this

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val fromSync = Throwable().stackTrace.any { it.className == ListViewModel::class.java.name && it.methodName in SYNC_ORDERS }
        if (fromSync) queue += context to block else inner.dispatch(context, block)
    }

    fun release() = queue.toList().also { queue.clear() }.forEach { (context, block) -> inner.dispatch(context, block) }
}

// Two threads; the block dispatched once [slowNext] is set waits a while before it runs, so work after it overtakes it.
private class SlowFirst : CoroutineDispatcher() {
    private val threads = Executors.newFixedThreadPool(2)
    private val busy = AtomicInteger()
    val slowNext = AtomicBoolean(false)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val slow = slowNext.getAndSet(false)
        busy.incrementAndGet()
        threads.execute {
            try {
                if (slow) Thread.sleep(SLOW_MILLIS)
                block.run()
            } finally {
                busy.decrementAndGet()
            }
        }
    }

    suspend fun idle() {
        while (busy.get() > 0) delay(POLL_MILLIS)
    }

    fun shutdown() = threads.shutdown()

    private companion object {
        const val SLOW_MILLIS = 300L
        const val POLL_MILLIS = 10L
    }
}

package io.github.kauri_off.kiroku.list

import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.defaultSort
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val set = Fixtures.reference
    private val today = set.now.atZone(FixtureTime.zone).toLocalDate()

    private class Subject(
        val graph: TestGraph,
        val saved: SavedStateHandle,
        val messages: ShellMessages,
        val list: ListViewModel,
    ) {
        val state: ListState get() = list.state.value

        val message: ShellMessage? get() = messages.current?.message

        // The snackbar's action, tapped wherever the user is by then.
        fun tapAction() = messages.ended(messages.current!!, actionPerformed = true)

        fun page(status: ListStatus = state.tab): ListPage = (state.content as ListContent.Tabs).pages.getValue(status)

        fun ids(status: ListStatus = state.tab): List<AnimeId> = page(status).rows.map { it.id }
    }

    private fun test(
        demo: (CoroutineScope) -> Demo = { Demo(Fixtures.reference, it) },
        saved: SavedStateHandle = SavedStateHandle(),
        lateObservers: Boolean = false,
        body: suspend TestScope.(Subject) -> Unit,
    ) = runTest(dispatcher) {
        val scope = foregroundScope()
        val graph = TestGraph(demo(scope), lateObservers)
        val messages = ShellMessages(scope, graph.list)
        val subject = Subject(graph, saved, messages, ListViewModel(graph, saved, messages))
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
        order(set.entries.filter { it.status == status }, ListSort.UpNext, set.airing::get, today) { it.anime.titles.romaji }
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
            val posted = s.messages.current!!
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
    fun `the sort sheet opens, closes, and a new sort orders only its tab again`() =
        test { s ->
            s.list.onEvent(ListEvent.OpenSort)
            assertTrue(s.state.sortSheetOpen)
            s.list.onEvent(ListEvent.DismissSort)
            assertFalse(s.state.sortSheetOpen)
            s.list.onEvent(ListEvent.OpenSort)
            s.list.onEvent(ListEvent.SelectSort(ListSort.Title))
            assertFalse(s.state.sortSheetOpen)
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
            val expected = order(completed, ListSort.LastUpdated, set.airing::get, today) { it.anime.titles.romaji }.map { it.anime.id }
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
    fun `the first load into an empty list orders every tab from what the sync brought`() =
        test(demo = ::firstLoad, lateObservers = true) { s ->
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
                        today,
                    ) { it.anime.titles.romaji }.map { it.anime.id }
                assertTrue(status.name, expected.isNotEmpty())
                assertEquals(status.name, expected, s.ids(status))
            }
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
    fun `the tab, each tab's sort and the filter survive process death`() {
        val saved = SavedStateHandle()
        test(saved = saved) { s ->
            s.list.onEvent(ListEvent.SelectTab(ListStatus.Completed))
            s.list.onEvent(ListEvent.SelectSort(ListSort.Score))
            s.list.onEvent(ListEvent.StartFilter)
            s.list.onEvent(ListEvent.FilterChanged("a"))
        }
        test(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })) { s ->
            assertEquals(ListStatus.Completed, s.state.tab)
            assertEquals(ListSort.Score, s.page().sort)
            assertEquals("a", s.state.filter)
        }
    }
}

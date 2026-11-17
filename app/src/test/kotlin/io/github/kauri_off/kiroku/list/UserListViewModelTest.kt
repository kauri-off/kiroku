package io.github.kauri_off.kiroku.list

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.demo.DemoUserLists
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.UserRepository
import io.github.kauri_off.kiroku.core.user.ScriptedUserLists
import io.github.kauri_off.kiroku.core.user.UserListKeeper
import io.github.kauri_off.kiroku.core.user.UserListPart
import io.github.kauri_off.kiroku.shell.ShellMessage
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.TestGraph
import io.github.kauri_off.kiroku.wiring.foregroundScope
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class UserListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val fixtures = Fixtures.reference
    private val otherName = fixtures.otherUserName

    // One whole read of the public user's list: a request a part.
    private val wholeRead = (fixtures.otherUserList.size + DemoUserLists.PART_SIZE - 1) / DemoUserLists.PART_SIZE

    private class App(
        val graph: TestGraph,
        val posted: MutableList<ShellMessage> = mutableListOf(),
    ) {
        val requests get() = graph.demo.userLists.requests
    }

    private fun TestScope.app(scope: CoroutineScope = foregroundScope()) = App(TestGraph(Demo(fixtures, scope)))

    private fun App.list(
        name: String = otherName,
        user: UserRepository = graph.user,
        saved: SavedStateHandle = SavedStateHandle(),
    ) = UserListViewModel(
        name,
        user,
        graph.list,
        graph.session,
        graph.connection,
        graph.clock,
        TitleLanguage.Romaji,
        saved,
        { posted += it },
        graph.background,
    )

    private val UserListViewModel.tabs get() = state.value.content as ListContent.Tabs

    private fun UserListViewModel.ids(status: EntryStatus) =
        tabs.pages
            .getValue(status)
            .rows
            .map { it.id }

    @Test
    fun `opened after the profile read the list, it asks for nothing and shows the whole list`() =
        runTest(dispatcher) {
            val app = app()
            app.graph.user.refreshList(otherName)
            assertEquals(wholeRead, app.requests.size)
            val airingAsked = app.graph.demo.airingSource.requests.size
            val list = app.list()
            showing(list.state, list.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(wholeRead, app.requests.size)
            assertEquals(
                fixtures.otherUserList.size,
                list.tabs.pages.values
                    .sumOf { it.rows.size },
            )
            assertNull(list.state.value.reading)
            assertEquals(airingAsked, app.graph.demo.airingSource.requests.size)
        }

    @Test
    fun `while the list is read its rows grow in part after part, with how far it is, in one ordering, and nothing is asked of AniList`() =
        runTest(dispatcher) {
            val app = app()
            app.graph.demo.server.latency = 10.milliseconds
            val airingAsked = app.graph.demo.airingSource.requests.size
            val list = app.list()
            val seen = mutableListOf<ListState>()
            backgroundScope.launch { list.state.collect { seen += it } }
            showing(list.shown)
            runCurrent()
            advanceTimeBy(35.milliseconds)
            runCurrent()
            val reading = list.state.value.reading!!
            assertTrue(reading.loaded in 1 until fixtures.otherUserList.size)
            assertEquals(
                reading.loaded,
                list.tabs.pages.values
                    .sumOf { it.rows.size },
            )
            advanceUntilIdle()
            assertNull(list.state.value.reading)
            val shown = seen.mapNotNull { it.reading?.loaded }.filter { it > 0 }
            assertEquals(shown.sorted(), shown)
            assertTrue(seen.mapNotNull { (it.content as? ListContent.Tabs)?.pages?.values }.flatten().all { it.ordering == 0 })
            val completed = fixtures.otherUserList.filter { it.status == ListStatus.Completed }
            assertEquals(
                order(completed, ListSort.LastUpdated, { null }) { it.anime.titles.romaji }.map { it.anime.id },
                list.ids(ListStatus.Completed),
            )
            assertEquals(airingAsked, app.graph.demo.airingSource.requests.size)
        }

    @Test
    fun `no event of the screen reaches a write`() =
        runTest(dispatcher) {
            val app = app()
            val list = app.list()
            showing(list.state, list.shown)
            runCurrent()
            advanceUntilIdle()
            val writes = app.graph.demo.server.requests
            val id = FixtureAnime.exactToWatch.id
            listOf(
                ListEvent.PlusOne(id),
                ListEvent.Edit(id),
                ListEvent.SelectTab(ListStatus.Completed),
                ListEvent.OpenSort,
                ListEvent.SelectSort(ListSort.Score),
                ListEvent.SelectDirection(SortDirection.Ascending),
                ListEvent.DismissSort,
                ListEvent.StartFilter,
                ListEvent.FilterChanged("a"),
                ListEvent.EndFilter,
                ListEvent.Refresh,
                ListEvent.Retry,
                ListEvent.Open(id),
                ListEvent.Search,
                ListEvent.SignIn,
                ListEvent.Back,
            ).forEach(list::onEvent)
            advanceUntilIdle()
            assertEquals(writes, app.graph.demo.server.requests)
            assertEquals(
                fixtures.entries,
                app.graph.list
                    .observeEntries()
                    .first(),
            )
            assertTrue(app.posted.isEmpty())
        }

    @Test
    fun `a row opens its title with the person's own entry, not the other user's`() =
        runTest(dispatcher) {
            val app = app()
            val list = app.list()
            showing(list.state, list.shown)
            runCurrent()
            advanceUntilIdle()
            val summary = list.summaryOf(FixtureAnime.exactToWatch.id)!!
            assertEquals(fixtures.entryFor(FixtureAnime.exactToWatch.id), summary.entry)
            val madeUp = fixtures.otherUserList.last().anime
            assertNull(list.summaryOf(madeUp.id)!!.entry)
            assertEquals(madeUp, list.summaryOf(madeUp.id)!!.anime)
        }

    @Test
    fun `an entry of a status the app does not know is on its own tab`() =
        runTest(dispatcher) {
            val app = app()
            val source = ScriptedUserLists(latency = 0.milliseconds)
            val unknown = fixtures.otherUserEntries.first().let { it.copy(values = it.values.copy(status = Fixtures.unknownListStatus)) }
            source.has(otherName, listOf(UserListPart(fixtures.otherUserEntries.drop(1) + unknown)))
            val keeper = UserListKeeper(source, backgroundScope, unexpected = { throw it })
            val list = app.list(user = keeper.beside(app.graph.user))
            showing(list.state, list.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ListStatus.entries + Fixtures.unknownListStatus, list.tabs.statuses)
            assertEquals(listOf(unknown.anime.id), list.ids(Fixtures.unknownListStatus))
            list.onEvent(ListEvent.SelectTab(Fixtures.unknownListStatus))
            advanceUntilIdle()
            assertEquals(Fixtures.unknownListStatus, list.state.value.tab)
        }

    @Test
    fun `a private list and an unknown name are each their own state, and are not asked again by the page`() =
        runTest(dispatcher) {
            val app = app()
            val private = app.list(fixtures.privateUserName)
            val nobody = app.list("no_such_user")
            showing(private.state, private.shown, nobody.state, nobody.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ListContent.Failed(ListFailure.PrivateList), private.state.value.content)
            assertEquals(ListContent.Failed(ListFailure.UnknownUser), nobody.state.value.content)
            assertEquals(2, app.requests.size)
        }

    @Test
    fun `offline the page fails with Retry, which reads the list once the connection is back`() =
        runTest(dispatcher) {
            val app = app()
            app.graph.demo.server.offline.value = true
            val list = app.list()
            showing(list.state, list.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ListContent.Failed(ListFailure.NoConnection), list.state.value.content)
            app.graph.demo.server.offline.value = false
            advanceUntilIdle()
            assertEquals(
                fixtures.otherUserList.size,
                list.tabs.pages.values
                    .sumOf { it.rows.size },
            )
        }

    @Test
    fun `a pull that fails over the list shown keeps it, with a message`() =
        runTest(dispatcher) {
            val app = app()
            val list = app.list()
            showing(list.state, list.shown)
            runCurrent()
            advanceUntilIdle()
            app.graph.demo.server.failingLoads.value = true
            list.onEvent(ListEvent.Refresh)
            advanceUntilIdle()
            assertEquals(
                fixtures.otherUserList.size,
                list.tabs.pages.values
                    .sumOf { it.rows.size },
            )
            assertEquals(listOf(ShellMessage(ShellMessage.Text.RefreshFailed(DataError.ServerError))), app.posted)
        }

    @Test
    fun `a new sort is core's order of the tab, from its top, and the other tabs keep theirs`() =
        runTest(dispatcher) {
            val app = app()
            val list = app.list()
            showing(list.state, list.shown)
            runCurrent()
            advanceUntilIdle()
            val planned = list.tabs.pages.getValue(ListStatus.Planned)
            list.onEvent(ListEvent.SelectTab(ListStatus.Completed))
            list.onEvent(ListEvent.SelectSort(ListSort.Score))
            advanceUntilIdle()
            val completed = fixtures.otherUserList.filter { it.status == ListStatus.Completed }
            assertEquals(
                order(completed, ListSort.Score, { null }) { it.anime.titles.romaji }.map { it.anime.id },
                list.ids(ListStatus.Completed),
            )
            assertEquals(
                1,
                list.tabs.pages
                    .getValue(ListStatus.Completed)
                    .ordering,
            )
            assertTrue(planned === list.tabs.pages.getValue(ListStatus.Planned))
        }

    @Test
    fun `a rotation during a read neither restarts it nor takes its progress back`() =
        runTest(dispatcher) {
            val app = app()
            app.graph.demo.server.latency = 10.milliseconds
            val list = app.list()
            val screen = showing(list.state, list.shown)
            runCurrent()
            advanceTimeBy(25.milliseconds)
            runCurrent()
            val before =
                list.state.value.reading!!
                    .loaded
            assertTrue(before > 0)
            screen.cancel()
            advanceTimeBy(ROTATION)
            showing(list.state, list.shown)
            runCurrent()
            assertTrue(
                (
                    list.state.value.reading
                        ?.loaded ?: Int.MAX_VALUE
                ) >= before,
            )
            advanceUntilIdle()
            assertEquals(wholeRead, app.requests.size)
        }

    @Test
    fun `a page left for good during a read asks for no further part`() =
        runTest(dispatcher) {
            val app = app()
            app.graph.demo.server.latency = 10.milliseconds
            val list = app.list()
            showing(list.state, list.shown)
            runCurrent()
            advanceTimeBy(25.milliseconds)
            runCurrent()
            val asked = app.requests.size
            assertTrue(asked in 1 until wholeRead)
            list.viewModelScope.cancel()
            advanceUntilIdle()
            assertEquals(asked, app.requests.size)
        }

    @Test
    fun `nothing is read before the page counts as shown`() =
        runTest(dispatcher) {
            val app = app()
            val list = app.list()
            showing(list.state)
            runCurrent()
            advanceUntilIdle()
            assertTrue(app.requests.isEmpty())
            assertEquals(ListContent.Loading, list.state.value.content)
        }

    @Test
    fun `the tab and the filter come back after a new process`() =
        runTest(dispatcher) {
            val app = app()
            val saved = SavedStateHandle()
            val list = app.list(saved = saved)
            showing(list.state, list.shown)
            runCurrent()
            advanceUntilIdle()
            list.onEvent(ListEvent.SelectTab(ListStatus.Completed))
            list.onEvent(ListEvent.FilterChanged("Kage"))
            val again = app.list(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
            showing(again.state, again.shown)
            runCurrent()
            advanceUntilIdle()
            assertEquals(ListStatus.Completed, again.state.value.tab)
            assertEquals("Kage", again.filter)
        }

    private companion object {
        val ROTATION = 1_000.milliseconds
    }
}

package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.BrowseSession
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    private val main = StandardTestDispatcher()

    @get:Rule
    val dispatcher = MainDispatcherRule(main)

    @Test
    fun `only a shown browse list loads or automatically retries after reconnecting`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            demo.server.offline.value = true
            val search =
                BrowseRequests(demo.search) {
                    if (demo.server.offline.value) Outcome.Failure(DataError.NoConnection) else Outcome.Success(Page(emptyList(), null))
                }
            val model = BrowseViewModel(BrowseList.ThisSeason, search, demo.connection, flowOf(false))
            showing(model.state)
            runCurrent()
            assertTrue(search.offsets.isEmpty())

            val showing = showing(model.shown)
            runCurrent()
            assertEquals(DataError.NoConnection, model.state.value.error)
            showing.cancel()
            runCurrent()
            demo.server.offline.value = false
            runCurrent()
            assertEquals(listOf(0), search.offsets)

            showing(model.shown)
            runCurrent()
            assertEquals(listOf(0, 0), search.offsets)
            assertEquals(1, search.sessions)
            assertEquals(Paged.Shown(emptyList<ResultRow>()), model.state.value.rows { error("Empty response") })
        }

    @Test
    fun `repeated footer events load a page once and overlapping titles keep their first positions`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val first = Ranked(7, FixtureAnime.exactToWatch)
            val second = Ranked(22, FixtureAnime.exactCaughtUp)
            val search =
                BrowseRequests(demo.search) { offset ->
                    Outcome.Success(if (offset == 0) Page(listOf(first), 19) else Page(listOf(first.copy(position = 20), second), null))
                }
            val model = BrowseViewModel(BrowseList.TopAiring, search, demo.connection, flowOf(false))
            showing(model.state, model.shown)
            runCurrent()
            model.loadMore()
            model.loadMore()
            runCurrent()
            model.loadMore()
            runCurrent()

            assertEquals(listOf(0, 19), search.offsets)
            assertEquals(1, search.sessions)
            assertEquals(listOf(first, second), model.state.value.titles)
        }

    @Test
    fun `changing the adult filter starts a new first page and drops a late continuation from the former filter`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val adult = MutableStateFlow(true)
            val previous = Ranked(1, FixtureAnime.exactToWatch)
            val safe = Ranked(1, FixtureAnime.exactCaughtUp)
            val late = CompletableDeferred<Outcome<Page<Ranked<Anime>>>>()
            val calls = mutableListOf<Pair<Int, Boolean>>()
            val search =
                object : SearchRepository by demo.search {
                    override fun openBrowse(list: BrowseList) =
                        BrowseSession { offset, includeAdult ->
                            calls += offset to includeAdult
                            when {
                                offset != 0 -> withContext(NonCancellable) { late.await() }
                                includeAdult -> Outcome.Success(Page(listOf(previous), 19))
                                else -> Outcome.Success(Page(listOf(safe), 25))
                            }
                        }
                }
            val model = BrowseViewModel(BrowseList.TopRated, search, demo.connection, adult)
            showing(model.state, model.shown)
            runCurrent()
            model.loadMore()
            runCurrent()
            adult.value = false
            runCurrent()
            assertEquals(listOf(0 to true, 19 to true, 0 to false), calls)
            assertEquals(listOf(safe), model.state.value.titles)
            late.complete(Outcome.Success(Page(listOf(previous), 99)))
            runCurrent()
            assertEquals(listOf(safe), model.state.value.titles)
            assertEquals(25, model.state.value.nextOffset)
        }

    private class BrowseRequests(
        delegate: SearchRepository,
        private val respond: (Int) -> Outcome<Page<Ranked<Anime>>>,
    ) : SearchRepository by delegate {
        val offsets = mutableListOf<Int>()
        var sessions = 0
            private set

        override fun openBrowse(list: BrowseList): BrowseSession {
            sessions++
            return BrowseSession { offset, _ ->
                offsets += offset
                respond(offset)
            }
        }
    }
}

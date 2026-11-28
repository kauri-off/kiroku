package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.SignInId
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    @Test
    fun `changing the period restarts at zero and retry stays in the selected period despite an old reply`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val winter = AnimeSeason(2026, Season.Winter)
            val fall = AnimeSeason(2025, Season.Fall)
            val old = Ranked(null, FixtureAnime.exactCaughtUp)
            val selected = Ranked(null, FixtureAnime.exactToWatch)
            val stale = CompletableDeferred<Outcome<Page<Ranked<Anime>>>>()
            val calls = mutableListOf<Pair<AnimeSeason, Int>>()
            val fallOffsets = mutableListOf<Int>()
            val search =
                object : SearchRepository by demo.search {
                    override fun openSeason(period: AnimeSeason) =
                        BrowseSession { offset, _ ->
                            calls += period to offset
                            when {
                                period == winter && offset == 0 -> Outcome.Success(Page(listOf(old), 19))
                                period == winter -> withContext(NonCancellable) { stale.await() }
                                offset == 0 -> Outcome.Success(Page(listOf(selected), 25))
                                offset !in fallOffsets -> {
                                    fallOffsets += offset
                                    Outcome.Failure(DataError.ServerError)
                                }
                                else -> Outcome.Success(Page(emptyList(), null))
                            }
                        }
                }
            val model =
                BrowseViewModel(browseRead { search.openSeason(winter) }, Anime::id, demo.connection, flowOf(false), period = winter)
            showing(model.state, model.shown)
            runCurrent()
            model.loadMore()
            runCurrent()

            model.selectPeriod(fall, browseRead { search.openSeason(fall) })
            runCurrent()
            stale.complete(Outcome.Success(Page(listOf(old), 99)))
            runCurrent()
            assertEquals(fall, model.state.value.period)
            assertEquals(listOf(selected), model.state.value.titles)
            assertEquals(25, model.state.value.nextOffset)

            model.loadMore()
            runCurrent()
            assertEquals(DataError.ServerError, model.state.value.error)
            assertEquals(listOf(selected), model.state.value.titles)
            model.retry()
            runCurrent()
            assertEquals(null, model.state.value.nextOffset)
            assertEquals(listOf(winter to 0, winter to 19, fall to 0, fall to 25, fall to 25), calls)
        }

    @Test
    fun `returning online validates the filter before resuming an offline continuation`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val mode = MutableStateFlow(true)
            val saved = CompletableDeferred<Unit>()
            var reads = 0
            val filter =
                flow {
                    if (++reads > 1) saved.await()
                    emitAll(mode)
                }
            val calls = mutableListOf<Pair<Int, Boolean>>()
            val previous = Ranked(1, FixtureAnime.exactToWatch)
            val current = Ranked(1, FixtureAnime.exactCaughtUp)
            val repo =
                object : SearchRepository by demo.search {
                    override fun openBrowse(list: BrowseList) =
                        BrowseSession { offset, adult ->
                            calls += offset to adult
                            when {
                                offset != 0 -> Outcome.Failure(DataError.NoConnection)
                                adult -> Outcome.Success(Page(listOf(previous), 20))
                                else -> Outcome.Success(Page(listOf(current), null))
                            }
                        }
                }
            val model = BrowseViewModel(BrowseList.TopRated, repo, demo.connection, filter)
            val showing = showing(model.state, model.shown)
            runCurrent()
            demo.server.offline.value = true
            model.loadMore()
            runCurrent()
            assertEquals(DataError.NoConnection, model.state.value.error)
            showing.cancel()
            runCurrent()
            mode.value = false
            demo.server.offline.value = false
            showing(model.state, model.shown)
            runCurrent()
            assertEquals(listOf(0 to true, 20 to true), calls)
            assertTrue(
                model.state.value.titles
                    .isEmpty(),
            )
            saved.complete(Unit)
            runCurrent()
            assertEquals(listOf(0 to true, 20 to true, 0 to false), calls)
            assertEquals(listOf(current), model.state.value.titles)
        }

    @Test
    fun `losing the suggestions owner drops loaded pages and a late continuation`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val filter = MutableStateFlow<Boolean?>(null)
            val owner = SignInId("first")
            val requests = mutableListOf<Pair<SignInId, Int>>()
            val first = Ranked(null, FixtureAnime.exactToWatch)
            val late = CompletableDeferred<Outcome<Page<Ranked<Anime>>>>()
            val search =
                object : SearchRepository by demo.search {
                    override fun openSuggestions(owner: SignInId) =
                        BrowseSession { offset, _ ->
                            requests += owner to offset
                            if (offset == 0) Outcome.Success(Page(listOf(first), 17)) else withContext(NonCancellable) { late.await() }
                        }
                }

            val model = BrowseViewModel(BrowseList.Suggestions, search, demo.connection, filter, owner)
            showing(model.state, model.shown)
            runCurrent()
            assertTrue(requests.isEmpty())
            filter.value = false
            runCurrent()
            assertEquals(listOf(first), model.state.value.titles)
            model.loadMore()
            runCurrent()
            filter.value = null
            runCurrent()
            assertTrue(
                model.state.value.titles
                    .isEmpty(),
            )
            late.complete(Outcome.Success(Page(listOf(Ranked(null, FixtureAnime.film)), null)))
            model.retry()
            runCurrent()
            assertTrue(
                model.state.value.titles
                    .isEmpty(),
            )
            assertEquals(listOf(owner to 0, owner to 17), requests)
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

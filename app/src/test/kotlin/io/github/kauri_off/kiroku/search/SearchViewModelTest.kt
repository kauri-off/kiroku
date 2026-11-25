package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val main = StandardTestDispatcher()

    @get:Rule
    val dispatcher = MainDispatcherRule(main)

    @Test
    fun `typing batches requests and a replaced query cancels its load`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val server = ScriptedSearch(demo.search)
            val model = SearchViewModel(server, demo.connection, SavedStateHandle())
            model.type("k")
            advanceTimeBy(500)
            assertTrue(server.requests.isEmpty())

            showing(model.state)
            runCurrent()
            advanceTimeBy(200)
            model.type("ka")
            runCurrent()
            advanceTimeBy(200)
            model.type("kag")
            runCurrent()
            advanceTimeBy(299)
            runCurrent()
            assertTrue(server.requests.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("kag" to 0), server.requests.map { it.key })

            val cancelled = server.requests.single()
            model.type("shi")
            runCurrent()
            cancelled.answer.complete(Outcome.Success(Page(listOf(FixtureAnime.exactToWatch), null)))
            advanceTimeBy(301)
            server.respond(Outcome.Success(Page(listOf(FixtureAnime.exactCaughtUp), null)))
            runCurrent()
            assertEquals(listOf("kag" to 0, "shi" to 0), server.requests.map { it.key })
            assertEquals("shi", model.state.value.query)
            assertEquals(listOf(FixtureAnime.exactCaughtUp), model.state.value.titles)
        }

    @Test
    fun `an anime link cancels an in flight request without starting a search and digits still search`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val server = ScriptedSearch(demo.search, delayedOffset = 0)
            val model = SearchViewModel(server, demo.connection, SavedStateHandle())
            showing(model.state)
            model.type("Steins;Gate 0")
            advanceTimeBy(301)
            val pending = server.requests.single()
            assertEquals("Steins;Gate 0" to 0, pending.key)

            val pasted = "  anime/5114/Fullmetal_Al?ref=share#top  "
            model.type(pasted)
            assertEquals(5114L, model.linkId?.value)
            assertEquals(pasted, model.query.text.toString())
            runCurrent()
            pending.answer.complete(Outcome.Success(Page(listOf(FixtureAnime.exactToWatch), 10)))
            advanceTimeBy(500)
            model.loadMore()
            model.retry()
            runCurrent()
            assertEquals(listOf(pending), server.requests)
            assertEquals(Paged.Shown<Anime>(emptyList()), model.state.value.rows { it })

            model.type("5114")
            advanceTimeBy(301)
            assertEquals("5114" to 0, server.requests.last().key)
            server.respond(Outcome.Success(Page(emptyList(), null)))
            runCurrent()
        }

    @Test
    fun `retry requests the failed offset and page loading keeps existing results`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val server = ScriptedSearch(demo.search)
            val model = SearchViewModel(server, demo.connection, SavedStateHandle())
            showing(model.state)
            model.type("kag")
            advanceTimeBy(301)
            server.respond(Outcome.Failure(DataError.NoConnection))
            runCurrent()
            assertEquals(DataError.NoConnection, model.state.value.error)

            model.retry()
            runCurrent()
            val first = FixtureAnime.exactToWatch
            server.respond(Outcome.Success(Page(listOf(first), 10)))
            runCurrent()
            model.loadMore()
            model.loadMore()
            runCurrent()
            assertEquals(listOf(first), model.state.value.titles)
            server.respond(Outcome.Failure(DataError.Refused))
            runCurrent()
            assertEquals(listOf(first), model.state.value.titles)
            assertEquals(DataError.Refused, model.state.value.error)

            model.retry()
            runCurrent()
            val second = FixtureAnime.exactCaughtUp
            server.respond(Outcome.Success(Page(listOf(first, second), null)))
            runCurrent()
            model.loadMore()
            runCurrent()
            assertEquals(listOf(0, 0, 10, 10), server.requests.map { it.key.second })
            assertEquals(listOf(first, second), model.state.value.titles)
            assertNull(model.state.value.nextOffset)
        }

    @Test
    fun `changing the adult filter starts a fresh search and rejects a late page from the previous filter`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val server = ScriptedSearch(demo.search, delayedOffset = 10)
            val adult = MutableStateFlow(true)
            val model = SearchViewModel(server, demo.connection, SavedStateHandle(), adult)
            showing(model.state)
            model.type("kag")
            advanceTimeBy(301)
            val first = FixtureAnime.exactToWatch
            val safe = FixtureAnime.exactCaughtUp
            server.respond(Outcome.Success(Page(listOf(first), 10)))
            runCurrent()
            model.loadMore()
            runCurrent()
            val stale = server.requests.last()
            adult.value = false
            runCurrent()
            assertTrue(
                model.state.value.titles
                    .isEmpty(),
            )
            assertEquals(listOf(0 to true, 10 to true, 0 to false), server.requests.map { it.key.second to it.adult })
            server.respond(Outcome.Success(Page(listOf(safe), 20)))
            runCurrent()
            stale.answer.complete(Outcome.Success(Page(listOf(first), 99)))
            runCurrent()
            assertEquals(listOf(safe), model.state.value.titles)
            assertEquals(20, model.state.value.nextOffset)
        }

    @Test
    fun `resuming search waits for the saved filter before restarting an interrupted page`() =
        runTest(main) {
            val demo = Demo(Fixtures.reference, backgroundScope)
            val server = ScriptedSearch(demo.search)
            val adult = MutableStateFlow(true)
            val stored = CompletableDeferred<Unit>()
            var reads = 0
            val choice =
                flow {
                    if (++reads > 1) stored.await()
                    emitAll(adult)
                }
            val model = SearchViewModel(server, demo.connection, SavedStateHandle(), choice)
            val shown = showing(model.state)
            model.type("kag")
            advanceTimeBy(301)
            server.respond(Outcome.Success(Page(listOf(FixtureAnime.exactToWatch), 10)))
            runCurrent()
            model.loadMore()
            runCurrent()
            shown.cancel()
            runCurrent()
            adult.value = false

            showing(model.state)
            runCurrent()
            assertEquals(listOf(0 to true, 10 to true), server.requests.map { it.key.second to it.adult })
            stored.complete(Unit)
            runCurrent()
            assertEquals(listOf(0 to true, 10 to true, 0 to false), server.requests.map { it.key.second to it.adult })
            server.respond(Outcome.Success(Page(listOf(FixtureAnime.exactCaughtUp), null)))
            runCurrent()
            assertEquals(listOf(FixtureAnime.exactCaughtUp), model.state.value.titles)
        }

    private fun SearchViewModel<Anime>.type(text: String) {
        query.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }
}

private class ScriptedSearch(
    delegate: SearchRepository,
    private val delayedOffset: Int? = null,
) : SearchRepository by delegate {
    class Request(
        val key: Pair<String, Int>,
        val adult: Boolean,
        val answer: CompletableDeferred<Outcome<Page<Anime>>> = CompletableDeferred(),
    )

    val requests = mutableListOf<Request>()

    fun respond(outcome: Outcome<Page<Anime>>) {
        val latest = requests.last()
        latest.answer.complete(outcome)
    }

    override suspend fun search(
        query: String,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Anime>> {
        val request = Request(query to offset, includeAdult)
        requests += request
        return if (offset == delayedOffset) withContext(NonCancellable) { request.answer.await() } else request.answer.await()
    }
}

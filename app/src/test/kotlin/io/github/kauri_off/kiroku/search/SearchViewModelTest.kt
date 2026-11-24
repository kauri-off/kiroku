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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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

    private fun SearchViewModel.type(text: String) {
        query.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }
}

private class ScriptedSearch(
    delegate: SearchRepository,
) : SearchRepository by delegate {
    class Request(
        val key: Pair<String, Int>,
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
        val request = Request(query to offset)
        requests += request
        return request.answer.await()
    }
}

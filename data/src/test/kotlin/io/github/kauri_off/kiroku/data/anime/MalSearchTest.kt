package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class MalSearchTest {
    @get:Rule
    internal val network = NetworkFixture()

    @Test
    fun `title search asks for summaries and maps the recorded results in order`() {
        val recorded = RecordedAnswers.named("search_adult")
        network.api.enqueue(answer(recorded.status, recorded.body, "application/json; charset=UTF-8"))

        val outcome = runBlocking { MalSearch(network.http).search(" bible black ", offset = 0, includeAdult = true) }
        val page = (outcome as Outcome.Success).value
        assertEquals(listOf(368L, 972L, 1401L, 2873L, 5347L, 13223L).map(::AnimeId), page.items.map { it.id })
        val first = page.items.first()
        assertEquals("Bible Black", first.titles.romaji)
        assertNull(page.nextOffset)

        val request = network.api.received()
        assertEquals("/v2/anime", request.url.encodedPath)
        assertEquals("bible black", request.url.queryParameter("q"))
        assertEquals("0", request.url.queryParameter("offset"))
        assertEquals("10", request.url.queryParameter("limit"))
        assertEquals(MalFields.SUMMARY, request.url.queryParameter("fields"))
        assertEquals("true", request.url.queryParameter("nsfw"))
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun `the next recorded offset loads another page with the default adult filter`() {
        for (name in listOf("search_first_page", "search_second_page")) {
            val recorded = RecordedAnswers.named(name)
            network.api.enqueue(answer(recorded.status, recorded.body, "application/json; charset=UTF-8"))
        }
        val search = MalSearch(network.http)
        val first = (runBlocking { search.search("naruto", offset = 0, includeAdult = false) } as Outcome.Success).value
        assertEquals(AnimeId(20), first.items.first().id)
        assertEquals(10, first.nextOffset)
        val nextOffset = checkNotNull(first.nextOffset)
        val second = (runBlocking { search.search("naruto", offset = nextOffset, includeAdult = false) } as Outcome.Success).value
        assertEquals(AnimeId(936), second.items.first().id)
        assertEquals(20, second.nextOffset)
        for (offset in listOf("0", "10")) {
            val request = network.api.received()
            assertEquals("naruto", request.url.queryParameter("q"))
            assertEquals(offset, request.url.queryParameter("offset"))
            assertNull(request.url.queryParameter("nsfw"))
        }
    }

    @Test
    fun `recorded MAL search refuses one and two characters and accepts three`() {
        val search = MalSearch(network.http)
        for ((query, name) in listOf("a" to "search_one_char", "an" to "search_two_chars", "ani" to "search_three_chars")) {
            val recorded = RecordedAnswers.named(name)
            network.api.enqueue(answer(recorded.status, recorded.body, "application/json; charset=UTF-8"))
            val result = runBlocking { search.search(query, offset = 0, includeAdult = false) }
            if (query.length < 3) {
                assertEquals(Outcome.Failure(DataError.InvalidRequest), result)
            } else {
                val first = (result as Outcome.Success).value.items.first()
                assertEquals(AnimeId(31519), first.id)
            }
            val request = network.api.received()
            assertEquals(query, request.url.queryParameter("q"))
        }
    }
}

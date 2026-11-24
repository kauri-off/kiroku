package io.github.kauri_off.kiroku.data.anime

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
}

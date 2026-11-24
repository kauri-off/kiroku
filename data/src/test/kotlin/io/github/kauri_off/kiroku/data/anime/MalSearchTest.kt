package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.BrowseList
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

        val outcome = runBlocking { MalSearch(network.http, network.clock).search(" bible black ", offset = 0, includeAdult = true) }
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
        val search = MalSearch(network.http, network.clock)
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
        val search = MalSearch(network.http, network.clock)
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

    @Test
    fun `the three rankings keep the returned order and show the ranking position`() {
        val search = MalSearch(network.http, network.clock)
        val rankings =
            listOf(
                Triple(BrowseList.TopAiring, "airing", listOf(61469L, 61967, 21, 50250, 61987, 55809, 60988, 51039, 64534, 56524)),
                Triple(BrowseList.TopRated, "all", listOf(52991L, 5114, 9253, 38524, 57555, 39486, 28977, 61316, 11061, 61469)),
                Triple(BrowseList.MostPopular, "bypopularity", listOf(16498L, 1535, 5114, 30276, 38000, 31964, 11757, 11061, 20, 40748)),
            )
        for ((list, type, ids) in rankings) {
            enqueue("ranking_$type")

            val page = (runBlocking { search.browse(list, offset = 0, includeAdult = false) } as Outcome.Success).value

            assertEquals(ids.map(::AnimeId), page.items.map { it.item.id })
            assertEquals((1..10).toList(), page.items.map { it.position })
            assertEquals(10, page.nextOffset)
            val request = network.api.received()
            assertEquals("/v2/anime/ranking", request.url.encodedPath)
            assertEquals(type, request.url.queryParameter("ranking_type"))
            assertEquals("0", request.url.queryParameter("offset"))
            assertEquals("10", request.url.queryParameter("limit"))
            assertEquals(MalFields.SUMMARY, request.url.queryParameter("fields"))
            assertNull(request.url.queryParameter("nsfw"))
            assertNull(request.headers["Authorization"])
        }
    }

    @Test
    fun `the current season is unranked and pages with the adult option`() {
        enqueue("season_fall_2026")
        enqueue("season_fall_2026_next")
        val search = MalSearch(network.http, network.clock)

        val first = (runBlocking { search.browse(BrowseList.ThisSeason, 0, includeAdult = false) } as Outcome.Success).value
        val next = checkNotNull(first.nextOffset)
        val second = (runBlocking { search.browse(BrowseList.ThisSeason, next, includeAdult = true) } as Outcome.Success).value

        assertEquals(
            listOf(61469L, 61967, 21, 61987, 61607, 64534, 61603, 62582, 235, 61323).map(::AnimeId),
            first.items.map { it.item.id },
        )
        assertEquals(
            listOf(61990L, 63367, 64445, 64505, 966, 59088, 8687, 61578, 60597, 63293).map(::AnimeId),
            second.items.map { it.item.id },
        )
        (first.items + second.items).forEach { assertNull(it.position) }
        assertEquals(10, next)
        assertEquals(20, second.nextOffset)
        for ((offset, nsfw) in listOf("0" to null, "10" to "true")) {
            val request = network.api.received()
            assertEquals("/v2/anime/season/2026/fall", request.url.encodedPath)
            assertEquals(offset, request.url.queryParameter("offset"))
            assertEquals("10", request.url.queryParameter("limit"))
            assertEquals(MalFields.SUMMARY, request.url.queryParameter("fields"))
            assertEquals(nsfw, request.url.queryParameter("nsfw"))
            assertNull(request.headers["Authorization"])
        }
    }

    @Test
    fun `an adult ranking page requests adult results at its own offset`() {
        enqueue("ranking_bypopularity")
        val search = MalSearch(network.http, network.clock)
        runBlocking { search.browse(BrowseList.MostPopular, 5, includeAdult = true) }
        val request = network.api.received()
        assertEquals("bypopularity", request.url.queryParameter("ranking_type"))
        assertEquals("5", request.url.queryParameter("offset"))
        assertEquals("true", request.url.queryParameter("nsfw"))
    }

    private fun enqueue(name: String) {
        val recorded = RecordedAnswers.named(name)
        network.api.enqueue(answer(recorded.status, recorded.body, "application/json; charset=UTF-8"))
    }
}

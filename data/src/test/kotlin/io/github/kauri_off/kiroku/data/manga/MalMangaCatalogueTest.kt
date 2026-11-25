package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaPublication
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

class MalMangaCatalogueTest {
    @get:Rule
    internal val network = NetworkFixture()

    @Test
    fun `search uses public manga summaries and continues from the recorded offset`() =
        runBlocking {
            enqueue("manga_search")
            enqueue("manga_search_next")
            val catalogue = MalMangaCatalogue(network.http)

            val first = (catalogue.search(" naruto ", 0, includeAdult = false) as Outcome.Success).value
            val naruto = first.items.first()
            assertEquals(MangaId(11), naruto.id)
            assertEquals("Naruto", naruto.titles.romaji)
            assertEquals(MangaFormat.Known.Manga, naruto.format)
            assertEquals(MangaPublication.Known.Finished, naruto.publication)
            assertEquals(72, naruto.volumes)
            assertEquals(700, naruto.chapters)
            assertEquals(8.09, naruto.meanScore)
            assertNull(naruto.synopsis)
            assertEquals(10, first.nextOffset)

            val second = (catalogue.search("naruto", checkNotNull(first.nextOffset), includeAdult = false) as Outcome.Success).value
            assertEquals(MangaId(153000), second.items.first().id)
            assertEquals(MangaFormat.Known.LightNovel, second.items[1].format)
            assertEquals(20, second.nextOffset)

            for (offset in listOf("0", "10")) {
                val request = network.api.received()
                assertEquals("/v2/manga", request.url.encodedPath)
                assertEquals("naruto", request.url.queryParameter("q"))
                assertEquals(offset, request.url.queryParameter("offset"))
                assertEquals("10", request.url.queryParameter("limit"))
                assertEquals(MalFields.MANGA_SUMMARY, request.url.queryParameter("fields"))
                assertNull(request.url.queryParameter("nsfw"))
                assertNull(request.headers["Authorization"])
            }
        }

    @Test
    fun `ranking keeps each recorded rank and retries at the same continuation offset`() =
        runBlocking {
            network.repeatReadsAfter = emptyList()
            enqueue("manga_ranking")
            network.api.enqueue(answer(503, body = ""))
            enqueue("manga_ranking_next")
            val catalogue = MalMangaCatalogue(network.http)

            val first = (catalogue.ranking(0, includeAdult = true) as Outcome.Success).value
            val offset = checkNotNull(first.nextOffset)
            assertEquals(Outcome.Failure(DataError.ServerError), catalogue.ranking(offset, includeAdult = true))
            val second = (catalogue.ranking(offset, includeAdult = true) as Outcome.Success).value

            assertEquals((1..10).toList(), first.items.map { it.position })
            assertEquals((11..20).toList(), second.items.map { it.position })
            assertEquals(
                MangaId(2),
                first.items
                    .first()
                    .item.id,
            )
            assertNull(
                first.items
                    .first()
                    .item.volumes,
            )
            assertEquals(
                MangaId(70345),
                second.items
                    .first()
                    .item.id,
            )
            assertEquals(20, second.nextOffset)

            for (expectedOffset in listOf("0", "10", "10")) {
                val request = network.api.received()
                assertEquals("/v2/manga/ranking", request.url.encodedPath)
                assertEquals("all", request.url.queryParameter("ranking_type"))
                assertEquals(expectedOffset, request.url.queryParameter("offset"))
                assertEquals("10", request.url.queryParameter("limit"))
                assertEquals(MalFields.MANGA_SUMMARY, request.url.queryParameter("fields"))
                assertEquals("true", request.url.queryParameter("nsfw"))
                assertNull(request.headers["Authorization"])
            }
        }

    @Test
    fun `client side invalid input sends no request`() =
        runBlocking {
            val catalogue = MalMangaCatalogue(network.http)
            val invalid = Outcome.Failure(DataError.InvalidRequest)
            assertEquals(invalid, catalogue.search(" ", 0, includeAdult = false))
            assertEquals(invalid, catalogue.search("naruto", -1, includeAdult = false))
            assertEquals(invalid, catalogue.ranking(-1, includeAdult = false))
            assertEquals(0, network.api.requestCount)
        }

    @Test
    fun `a recorded previous link cannot be used as the next page`() =
        runBlocking {
            val recorded = RecordedAnswers.named("manga_ranking_next")
            network.api.enqueue(answer(recorded.status, recorded.body.replace("next", "after").replace("previous", "next")))
            val catalogue = MalMangaCatalogue(network.http)

            assertEquals(Outcome.Failure(DataError.ServerError), catalogue.ranking(10, includeAdult = false))
        }

    private fun enqueue(name: String) {
        val recorded = RecordedAnswers.named(name)
        network.api.enqueue(answer(recorded.status, recorded.body))
    }
}

package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaPublication
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test
    fun `a recorded search with no main title recovers available alternate names and retains the rest of the page`() =
        runBlocking {
            val recorded = RecordedAnswers.named("manga_search")
            val original = Json.parseToJsonElement(recorded.body).jsonObject
            val hits = original.getValue("data").jsonArray
            val catalogue = MalMangaCatalogue(network.http)

            for ((index, keep) in listOf(0 to null, 0 to "ja", 1 to "synonyms")) {
                val hit = hits[index].jsonObject
                val node = hit.getValue("node").jsonObject
                val alternatives = node.getValue("alternative_titles").jsonObject
                val alternate = if (keep == null) alternatives else JsonObject(alternatives.filterKeys { it == keep })
                val unnamed = JsonObject(node - "title" + ("alternative_titles" to alternate))
                val mutant = JsonObject(hit + ("node" to unnamed))
                val page = JsonArray(hits.toMutableList().apply { set(index, mutant) })
                val mutatedPage = JsonObject(original + ("data" to page))
                network.api.enqueue(answer(recorded.status, mutatedPage.toString()))

                val result = (catalogue.search("naruto", 0, includeAdult = false) as Outcome.Success).value
                val expected = alternate.getValue(keep ?: "en")
                val name = if (expected is JsonArray) expected.first() else expected
                assertEquals(name.jsonPrimitive.content, result.items[index].titles.romaji)
                assertEquals(
                    hits.map {
                        it.jsonObject
                            .getValue("node")
                            .jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    },
                    result.items.map { it.id.value.toString() },
                )
                assertEquals(10, result.nextOffset)
            }
            assertEquals(List(3) { setOf("title") }, network.unexpected.filterIsInstance<RepairedAnswer>().map { it.fields })
        }

    @Test
    fun `ranking reads an altered wire rank and continuation independent of row count`() =
        runBlocking {
            val recorded = RecordedAnswers.named("manga_ranking")
            val original = Json.parseToJsonElement(recorded.body).jsonObject
            val hits = original.getValue("data").jsonArray
            val rank = JsonObject(mapOf("rank" to JsonPrimitive(6)))
            val first = JsonObject(hits.first().jsonObject + ("ranking" to rank))
            val next =
                original
                    .getValue("paging")
                    .jsonObject
                    .getValue("next")
                    .jsonPrimitive.content
            val paging = JsonObject(mapOf("next" to JsonPrimitive(next.replace("offset=10", "offset=47"))))
            val page = JsonObject(original + mapOf("data" to JsonArray(listOf(first) + hits.drop(1)), "paging" to paging))
            network.api.enqueue(answer(recorded.status, page.toString()))

            val result = (MalMangaCatalogue(network.http).ranking(0, includeAdult = false) as Outcome.Success).value
            assertEquals(6, result.items.first().position)
            assertEquals(
                MangaId(2),
                result.items
                    .first()
                    .item.id,
            )
            assertEquals(47, result.nextOffset)
        }

    @Test
    fun `hidden ranking cannot send its first request or a transport retry`() =
        runBlocking {
            val shown = AtomicBoolean(false)
            network.answers {
                shown.set(false)
                answer(503, body = "")
            }
            val catalogue = MalMangaCatalogue(network.http)

            assertNull(catalogue.ranking(10, false, shown::get))
            assertEquals(0, network.api.requestCount)
            shown.set(true)
            assertNull(catalogue.ranking(10, false, shown::get))
            assertEquals(1, network.api.requestCount)
            assertEquals(
                "10",
                network.api
                    .received()
                    .url
                    .queryParameter("offset"),
            )
        }

    private fun enqueue(name: String) {
        val recorded = RecordedAnswers.named(name)
        network.api.enqueue(answer(recorded.status, recorded.body))
    }
}

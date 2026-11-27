package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.CLIENT_ID_HEADER
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import io.github.kauri_off.kiroku.data.recorder.insideACheckout
import io.github.kauri_off.kiroku.data.recorder.ownerFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

class MalPublicMangaListsTest {
    @get:Rule
    internal val network = NetworkFixture()

    private val lists by lazy { MalPublicMangaLists(network.http) }

    @Test
    fun `a named read requests every status with a client id and treats a terminal empty envelope as success`() =
        runBlocking {
            network.always(answer(200, EMPTY).newBuilder().addHeader("Set-Cookie", "test=ignored").build())
            val name = "x/y?offset=3 %40me"
            repeat(2) {
                val parts = mutableListOf<List<MangaListEntry>>()
                assertEquals(Outcome.Success(Unit), lists.read(name) { parts += it })
                assertEquals(listOf(emptyList<MangaListEntry>()), parts)
                val request = network.api.received()
                assertEquals("GET", request.method)
                assertEquals(listOf("v2", "users", name, "mangalist"), request.url.pathSegments)
                assertEquals(MangaListQuery + ("offset" to "0"), request.url.queryParameterNames.associateWith(request.url::queryParameter))
                assertEquals(NetworkFixture.CLIENT_ID, request.headers[CLIENT_ID_HEADER])
                assertNull(request.headers[AUTHORIZATION])
                assertNull(request.headers["Cookie"])
            }
            assertTrue(lists.sameUser("Some_User", "some_user"))
            assertFalse(lists.sameUser("Kaori", "kaori"))
        }

    @Test
    fun `reserved names are never sent and a missing client id never reaches the network`() =
        runBlocking {
            network.clientId = ""
            for (name in listOf("", ".", "..", "@me", "@ME")) {
                assertEquals(Outcome.Failure(DataError.UnknownUser), lists.read(name) { error("Unexpected page") })
            }
            assertEquals(Outcome.Failure(DataError.NoClientId), lists.read("sample") { error("Unexpected page") })
            assertEquals(0, network.api.requestCount)
            assertEquals(Duration.ZERO, network.http.refusedFor())
        }

    @Test
    fun `unverified privacy and unknown signatures retain their general MAL error handling`() =
        runBlocking {
            val animePrivacy = RecordedAnswers.named("list_private").body
            val cases =
                listOf(
                    answer(403, animePrivacy) to DataError.Refused,
                    answer(403, "Blocked", type = "text/plain") to DataError.ServerError,
                    answer(429, "") to DataError.Refused,
                    answer(404, "") to DataError.NotFound,
                    answer(404, """{"error":"not_found"} trailing""") to DataError.NotFound,
                )
            for ((reply, error) in cases) {
                network.always(reply)
                assertEquals(Outcome.Failure(error), lists.read("sample") { error("Unexpected page") })
                assertEquals(error == DataError.Refused, !network.http.refusedFor().isZero)
                network.http.backOff.answered(network.http.backOff.mark())
            }
        }

    @Test
    fun `redirects are never followed even to the original API origin`() =
        runBlocking {
            network.always(
                answer(302, "").newBuilder().addHeader("Location", network.api.url("/v2/users/@me/mangalist").toString()).build(),
            )
            assertEquals(Outcome.Failure(DataError.ServerError), lists.read("sample") { error("Unexpected page") })
            assertEquals(1, network.api.requestCount)
        }

    @Test
    fun `only complete terminal OK envelopes are empty lists`() =
        runBlocking {
            val malformed =
                listOf(
                    answer(204, ""),
                    answer(201, EMPTY),
                    answer(200, EMPTY, type = "text/html"),
                    answer(200, EMPTY + " trailing"),
                    answer(200, """{"data":[]}"""),
                    answer(200, """{"paging":{}}"""),
                    answer(200, """{"data":[],"paging":[] }"""),
                    answer(200, """{"data":[],"paging":{},"error":"not_found"}"""),
                    answer(200, """{"data":[],"paging":{"next":"https://example.test/?offset=1"}}"""),
                )
            for (reply in malformed) {
                network.always(reply)
                assertEquals(Outcome.Failure(DataError.ServerError), lists.read("sample") { error("Unexpected page") })
            }
        }

    @Test
    fun `cancellation stops the suspended response body read without publishing a page or retrying`() =
        runBlocking {
            network.always(answer(200, EMPTY).newBuilder().bodyDelay(5, TimeUnit.SECONDS).build())
            val read = launch(Dispatchers.Default) { lists.read("sample") { error("Unexpected page") } }
            network.api.received()
            withTimeout(2_000) { read.cancelAndJoin() }
            assertTrue(read.isCancelled)
            assertEquals(1, network.api.requestCount)
            assertTrue(network.unexpected.isEmpty())
        }

    @Test
    fun `opt-in replay of complete public captures exercises mapping and offset validation`() =
        runBlocking {
            val paths = System.getProperty("kiroku.publicMangaReplay")
            assumeTrue("Public Manga replay is opt-in", !paths.isNullOrBlank())
            OwnerRecordings.read("Public Manga replay") {
                val captures =
                    checkNotNull(paths).split(File.pathSeparator).map { path ->
                        ownerFolder(path).listFiles().orEmpty().map { file ->
                            check(file.isFile && !insideACheckout(file))
                            file.name.substringAfterLast('.', "").let { check(it == "json") }
                            Captured(
                                file.name
                                    .split('.')
                                    .dropLast(1)
                                    .last()
                                    .toInt(),
                                file.readText(),
                            )
                        }
                    }
                val unknown = captures.single { it.singleOrNull()?.status == 404 }.single()
                network.always(answer(unknown.status, unknown.body))
                val replied = lists.read("sample") { error("Unexpected page") }
                check(replied == Outcome.Failure(DataError.UnknownUser) && network.http.refusedFor().isZero)
                network.api.received()

                val downloads = captures.filterNot { it.singleOrNull()?.status == 404 }.map(::orderedPages)
                check(downloads.any { it.size > 1 })
                for (pages in downloads) {
                    val offsets = pages.map { it.first }
                    val bodies = pages.toMap()
                    network.answers { request ->
                        answer(200, bodies.getValue(request.url.queryParameter("offset")!!.toInt()).body)
                    }
                    val parts = mutableListOf<List<MangaListEntry>>()
                    val before = network.api.requestCount
                    val result =
                        lists.read("sample") {
                            parts += it
                            check(network.api.requestCount == before + parts.size)
                        }
                    check(result == Outcome.Success(Unit) && parts.size == pages.size)
                    for ((index, page) in pages.withIndex()) {
                        val request = network.api.received()
                        check(request.url.pathSegments == listOf("v2", "users", "sample", "mangalist"))
                        check(request.url.queryParameter("offset") == offsets[index].toString())
                        check(request.url.queryParameter("limit") == MangaListQuery.getValue("limit"))
                        check(request.headers[CLIENT_ID_HEADER] == NetworkFixture.CLIENT_ID)
                        check(request.headers[AUTHORIZATION] == null && request.headers["Cookie"] == null)
                        checkMapped(parts[index], page.second)
                    }
                }

                val pages = downloads.first { it.size > 1 }
                val first = pages.first().second
                val second = pages[1].second
                val nextOffset = pages[1].first
                val badOffsets = listOf("0", (nextOffset + 1).toString(), "100000", "$nextOffset&offset=$nextOffset", "bogus")
                for (offset in badOffsets) {
                    val next = JsonPrimitive("https://example.test/?offset=$offset")
                    val malformed = JsonObject(first.json + ("paging" to JsonObject(mapOf("next" to next))))
                    network.always(answer(200, malformed.toString()))
                    check(lists.read("sample") { error("Unexpected page") } == Outcome.Failure(DataError.ServerError))
                    check(
                        network.api
                            .received()
                            .url
                            .queryParameter("offset") == "0",
                    )
                }
                for (tail in listOf(EMPTY, JsonObject(second.json + ("data" to first.json.getValue("data"))).toString())) {
                    network.answers { request ->
                        answer(200, if (request.url.queryParameter("offset") == "0") first.body else tail)
                    }
                    var parts = 0
                    check(lists.read("sample") { parts++ } == Outcome.Failure(DataError.ServerError))
                    check(parts == 1)
                    check(
                        network.api
                            .received()
                            .url
                            .queryParameter("offset") == "0",
                    )
                    check(
                        network.api
                            .received()
                            .url
                            .queryParameter("offset") == nextOffset.toString(),
                    )
                }
            }
        }

    private class Captured(
        val status: Int,
        val body: String,
    ) {
        val json get() = checkNotNull(body.strictMangaObject())

        fun offset(rel: String): Int? =
            json
                .getValue("paging")
                .jsonObject[rel]
                ?.jsonPrimitive
                ?.content
                ?.toHttpUrl()
                ?.queryParameter("offset")
                ?.toInt()
    }

    private fun orderedPages(capture: List<Captured>): List<Pair<Int, Captured>> {
        check(capture.isNotEmpty() && capture.all { it.status == 200 })
        val remaining = capture.toMutableList()
        var previous: Int? = null
        var offset = 0
        return buildList {
            while (true) {
                val page = remaining.single { it.offset("previous") == previous }
                remaining.remove(page)
                add(offset to page)
                previous = offset
                val next = page.offset("next") ?: break
                check(
                    next == offset +
                        page.json
                            .getValue("data")
                            .jsonArray.size &&
                        next > offset,
                )
                offset = next
            }
            check(remaining.isEmpty())
        }
    }

    private fun checkMapped(
        entries: List<MangaListEntry>,
        capture: Captured,
    ) {
        val rows = capture.json.getValue("data").jsonArray
        check(entries.size == rows.size)
        for ((entry, row) in entries.zip(rows)) {
            val node = row.jsonObject.getValue("node").jsonObject
            val status = row.jsonObject.getValue("list_status").jsonObject
            check(
                entry.id.value ==
                    node
                        .getValue("id")
                        .jsonPrimitive.content
                        .toLong(),
            )
            check(entry.titles.romaji == node.getValue("title").jsonPrimitive.content)
            val expectedTime = OffsetDateTime.parse(status.getValue("updated_at").jsonPrimitive.content).toInstant()
            check(entry.updatedAt == expectedTime)
            check(entry.status.code == status.getValue("status").jsonPrimitive.content)
            check(entry.chaptersRead == status.getValue("num_chapters_read").jsonPrimitive.int)
            check(entry.volumesRead == status.getValue("num_volumes_read").jsonPrimitive.int)
            val score = status.getValue("score").jsonPrimitive.int
            check(entry.score == score.takeIf { it > 0 } && entry.explicitlyUnrated == (score == 0))
        }
    }

    private companion object {
        const val EMPTY = """{"data":[],"paging":{}}"""
    }
}

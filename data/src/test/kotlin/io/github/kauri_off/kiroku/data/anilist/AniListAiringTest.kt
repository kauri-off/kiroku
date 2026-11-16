package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.RecordedAt
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.idsOf
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.json
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.pagesOf
import io.github.kauri_off.kiroku.data.anilist.AniListServer.Companion.reply
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.network.MalBackOff
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.dropped
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The client against a local AniList that answers M3-01's recordings: what the port obliges, the paging, the pace.
@OptIn(ExperimentalCoroutinesApi::class)
class AniListAiringTest {
    private val network = NetworkFixture()
    private val aniList = AniListServer(network)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(network).around(aniList)

    private val since: Instant = RecordedAt.minus(Duration.ofHours(75))

    private suspend fun TestScope.fetch(ids: List<Int>): Map<AnimeId, Outcome<FetchedAiring?>> =
        aniList.client(this).fetch(ids.map(::titleWithId), since)

    // Every recorded node of [case]'s pages that belongs to the [index]-th entry of its MAL id [id], in order.
    private fun recordedEpisodes(
        case: String,
        id: Int,
        index: Int = 0,
    ): List<ScheduledEpisode> =
        pagesOf(case)
            .flatMap { file -> mediaOf(json(file)).filter { it.jsonObject["idMal"]!!.jsonPrimitive.int == id }.drop(index).take(1) }
            .flatMap { it.jsonObject["airingSchedule"]!!.jsonObject["nodes"]!!.jsonArray }
            .map {
                ScheduledEpisode(
                    it.jsonObject["episode"]!!.jsonPrimitive.int,
                    Instant.ofEpochSecond(it.jsonObject["airingAt"]!!.jsonPrimitive.long),
                )
            }

    private fun whole(
        case: String,
        id: Int,
        total: Int?,
        index: Int = 0,
    ): Outcome<FetchedAiring?> = Outcome.Success(FetchedAiring(AiringSchedule(recordedEpisodes(case, id, index), total)))

    private fun spacing(): Long = AniListPace.spacingFor(AniListRecordings.limit).inWholeMilliseconds

    private fun gaps(): List<Long> = aniList.requests.map { it.second }.zipWithNext { a, b -> b - a }

    @Test
    fun `a title's schedule is answered whole, every page followed, in as many requests as M3-01 recorded`() =
        runTest {
            for ((case, id, total) in listOf(
                Triple("airing_weekly", 61987, 12),
                Triple("on_a_break", 21, null),
                Triple("hundreds_of_episodes", 235, null),
            )) {
                aniList.requests.clear()
                val answer = fetch(listOf(id)).of(id)

                assertEquals(case, whole(case, id, total), answer)
                assertEquals(case, pagesOf(case).size, aniList.requests.size)
            }
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
        }

    @Test
    fun `every episode at or after the instant asked for and the latest aired one are in the answer, complete from the start`() =
        runTest {
            val schedule = (fetch(listOf(21)).of(21) as Outcome.Success).value!!
            val recorded = recordedEpisodes("on_a_break", 21)
            val latestAired = recorded.filter { it.airsAt <= RecordedAt }.maxBy { it.airsAt }

            assertNull(schedule.completeFrom)
            assertTrue(schedule.schedule.episodes.containsAll(recorded.filter { it.airsAt >= since }))
            assertTrue(latestAired in schedule.schedule.episodes)
            assertTrue(latestAired.airsAt < since)
        }

    @Test
    fun `a title AniList knows without episode times is an empty schedule, an id it does not know is null`() =
        runTest {
            val answers = fetch(idsOf("unknown_among_others"))

            assertEquals(Outcome.Success(FetchedAiring(AiringSchedule(emptyList(), 24))), answers.of(62734))
            assertEquals(Outcome.Success(null), answers.of(9874))
            assertEquals(whole("airing_weekly", 61987, 12), answers.of(61987))
            assertEquals(Outcome.Success(null), fetch(idsOf("unknown_alone")).of(9874))
            assertEquals(
                Outcome.Success(FetchedAiring(AiringSchedule(emptyList(), 12))),
                fetch(idsOf("not_yet_aired_without_time")).of(62841),
            )
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
        }

    @Test
    fun `of two entries with one MAL id the one whose latest episode aired last is chosen, its pages followed`() =
        runTest {
            assertEquals(whole("totals_differ_by_more", 51039, 62, index = 1), fetch(listOf(51039)).of(51039))
            assertEquals(3, aniList.requests.size)
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
        }

    @Test
    fun `the rule for two entries, on the recorded pair, before either aired and without times`() {
        val first = aniListPage(AniListRecordings.body("totals_differ_by_more")) {}!!.entries
        val before = Instant.parse("2022-01-01T00:00:00Z")
        val untimed =
            listOf(
                "releasing_without_times",
                "not_yet_aired_without_time",
            ).map { aniListPage(AniListRecordings.body(it)) {}!!.entries.single() }

        assertEquals(62, chosen(first, RecordedAt).total)
        assertEquals(
            52,
            chosen(
                first,
                first[1]
                    .episodes
                    .first()
                    .airsAt
                    .minusSeconds(1),
            ).total,
        )
        assertEquals(52, chosen(first.reversed(), before).total)
        assertEquals(62, chosen(first.reversed(), first[1].episodes.first().airsAt).total)
        assertEquals(untimed[0], chosen(untimed, RecordedAt))
        assertEquals(untimed[1], chosen(untimed.reversed(), RecordedAt))
    }

    @Test
    fun `with more ids than a page holds every page of titles is read, and a missing id is asked again before it is unknown`() =
        runTest {
            val ids = idsOf("more_than_one_page")
            aniList.answer = { asked ->
                when {
                    asked.schedulePage > 1 -> laterPageOf(asked)
                    "more_than_one_page" !in AniListRecordings.byRequest[asked.copy(ids = asked.ids.sorted())].orEmpty() -> unknown(asked)
                    else -> aniList.recorded(asked)
                }
            }
            val answers = fetch(ids)
            val known =
                (
                    mediaOf(
                        json("more_than_one_page"),
                    ) + mediaOf(json("more_than_one_page_2"))
                ).map { it.jsonObject["idMal"]!!.jsonPrimitive.int }
            val unknown = ids - known.toSet()

            assertEquals(5, unknown.size)
            for (id in unknown) assertEquals(Outcome.Success(null), answers.of(id))
            for (id in known) assertTrue("$id ${answers.of(id)}", (answers.of(id) as? Outcome.Success)?.value != null)
            assertEquals(11, (answers.of(61469) as Outcome.Success).value!!.schedule.totalEpisodes)
            assertEquals(listOf(Asked(ids, 1, 1), Asked(ids, 2, 1), Asked(unknown, 1, 1)), aniList.asked().take(3))
            assertEquals(4, aniList.requests.size)
            assertTrue(aniList.asked()[3].let { it.schedulePage == 2 && it.ids.size < 50 })
        }

    @Test
    fun `300 ids are asked in the fewest requests, none sooner than half the limit AniList reports allows`() =
        runTest {
            aniList.answer = ::weeklyFor
            val ids = (1..300).toList()

            val answers = fetch(ids)

            assertEquals(6, aniList.requests.size)
            assertEquals((1..6).map { Asked(ids, it, 1) }, aniList.asked())
            assertTrue(ids.all { answers.of(it) is Outcome.Success })
            assertTrue(gaps().toString(), gaps().all { it >= spacing() })
            assertEquals(4000L, spacing())

            fetch(listOf(1))
            assertTrue(gaps().last() >= spacing())
        }

    @Test
    fun `a higher limit reported shortens the spacing, never below what AniList's usual limit allows`() =
        runTest {
            aniList.answer = { weeklyFor(it, limit = 90) }
            fetch((1..100).toList())
            aniList.answer = { weeklyFor(it, limit = 1000) }
            fetch((1..150).toList())

            val shortest = AniListPace.spacingFor(AniListPace.HIGHEST_LIMIT).inWholeMilliseconds
            assertEquals(4, gaps().size)
            assertTrue(gaps().toString(), gaps().all { it in shortest..shortest + 1 })
        }

    @Test
    fun `a page that fails leaves its titles not fetched and the others fetched, after a wait`() =
        runTest {
            aniList.answer = { if (it.page == 1) reply(500, "") else weeklyFor(it) }
            val answers = fetch((1..100).toList())

            for (id in 1..50) assertEquals(Outcome.Failure(DataError.ServerError), answers.of(id))
            for (id in 51..100) assertTrue(answers.of(id) is Outcome.Success)
            assertTrue(gaps().single() >= AniListPace.AfterFailure.inWholeMilliseconds)
        }

    @Test
    fun `a request that gets no answer fails its titles, and offline the call stops`() =
        runTest {
            aniList.answer = { if (it.page == 1) dropped() else weeklyFor(it) }
            val online = fetch((1..100).toList())

            assertEquals(Outcome.Failure(DataError.ServerError), online.of(1))
            assertTrue(online.of(51) is Outcome.Success)
            assertEquals(2, aniList.requests.size)

            network.online = false
            aniList.answer = { dropped() }
            val offline = fetch((1..100).toList())

            assertEquals(Outcome.Failure(DataError.NoConnection), offline.of(1))
            assertEquals(Outcome.Failure(DataError.NoConnection), offline.of(51))
            assertEquals(3, aniList.requests.size)
        }

    @Test
    fun `a refusal stops the call, holds every request back for its wait, and leaves MAL's back-off at zero`() =
        runTest {
            aniList.answer = { if (it.page == 2) reply(429, "", headers = mapOf("Retry-After" to "120")) else weeklyFor(it) }
            val answers = fetch((1..150).toList())

            assertTrue(answers.of(1) is Outcome.Success)
            assertEquals(Outcome.Failure(DataError.Refused), answers.of(51))
            assertEquals(Outcome.Failure(DataError.Refused), answers.of(101))
            assertEquals(2, aniList.requests.size)
            assertEquals(Duration.ZERO, network.http.backOff.remaining())

            advanceTimeBy(119.seconds)
            assertEquals(Outcome.Failure(DataError.Refused), fetch(listOf(1)).of(1))
            assertEquals(2, aniList.requests.size)

            advanceTimeBy(1.seconds)
            aniList.answer = { reply(403, """{"errors":[{"message":"x","status":403}],"data":null}""") }
            assertEquals(Outcome.Failure(DataError.Refused), fetch(listOf(1)).of(1))
            assertEquals(3, aniList.requests.size)
            assertEquals(Duration.ZERO, network.http.backOff.remaining())

            advanceTimeBy(AniListPace.FirstRefusal * 2 - 1.milliseconds)
            aniList.answer = ::weeklyFor
            assertEquals(Outcome.Failure(DataError.Refused), fetch(listOf(1)).of(1))
            advanceTimeBy(1.milliseconds)
            assertTrue(fetch(listOf(1)).of(1) is Outcome.Success)
        }

    @Test
    fun `an answer of AniList's does not end MAL's back-off`() =
        runTest {
            network.always(answer(429))
            network.http.call { network.things.value.read() }
            aniList.answer = ::weeklyFor

            assertTrue(fetch(listOf(1)).of(1) is Outcome.Success)
            assertEquals(MalBackOff.First, network.http.backOff.remaining())
        }

    @Test
    fun `a request carries the MAL ids and the User-Agent, and no client id, token or cookie`() =
        runTest {
            fetch(idsOf("unknown_among_others"))
            val request = aniList.requests.single().first

            assertEquals("POST", request.method)
            assertEquals(
                setOf("Host", "Connection", "Accept-Encoding", "User-Agent", "Accept", "Content-Type", "Content-Length"),
                request.headers.names().toSet(),
            )
            assertEquals("Kiroku/1.2.3", request.headers["User-Agent"])
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            assertEquals(setOf("query", "variables"), body.keys)
            assertEquals(AniListSchedule.QUERY, body["query"]!!.jsonPrimitive.content)
            assertEquals(setOf("ids", "page", "schedulePage"), body["variables"]!!.jsonObject.keys)
            assertEquals(Asked(idsOf("unknown_among_others"), 1, 1), aniList.asked().single())
            assertFalse(NetworkFixture.CLIENT_ID in request.headers.toString() + request.url + request.body!!.utf8())
        }

    @Test
    fun `a title whose answer cannot be read is not fetched and is reported, the others are`() =
        runTest {
            aniList.answer =
                {
                    reply(
                        200,
                        json(
                            "unknown_among_others",
                        ).edited(MEDIA + listOf("0", "airingSchedule", "nodes", "2", "episode"), null).toString(),
                    )
                }
            val answers = fetch(idsOf("unknown_among_others"))

            assertEquals(Outcome.Failure(DataError.ServerError), answers.of(61987))
            assertEquals(Outcome.Success(FetchedAiring(AiringSchedule(emptyList(), 24))), answers.of(62734))
            assertEquals(Outcome.Success(null), answers.of(9874))
            assertEquals(listOf(AniListOddAnswer::class.java), network.unexpected.map { it.javaClass })
        }

    @Test
    fun `a later page whose entries are not those of the first fails the title, it is never unknown`() =
        runTest {
            aniList.answer = { asked ->
                val recorded = aniList.recorded(asked)
                if (asked.schedulePage != 2) {
                    recorded
                } else {
                    val page = json("totals_differ_by_more_2")
                    reply(200, page.edited(MEDIA, JsonArray(mediaOf(page).take(1))).toString())
                }
            }

            assertEquals(Outcome.Failure(DataError.ServerError), fetch(listOf(51039)).of(51039))
            assertEquals(2, aniList.requests.size)
        }

    @Test
    fun `an id no request can carry fails alone`() =
        runTest {
            aniList.answer = ::weeklyFor
            val answers = aniList.client(this).fetch(listOf(titleWithId(1), titleWithId(1).copy(id = AnimeId(1L shl 40))), since)

            assertEquals(Outcome.Failure(DataError.InvalidRequest), answers[AnimeId(1L shl 40)])
            assertTrue(answers.of(1) is Outcome.Success)
            assertEquals(listOf(listOf(1)), aniList.asked().map { it.ids })
        }

    // The recorded weekly title once for each id of the page asked, under that id, and the recorded mark of a page that
    // has more titles after it or none.
    private fun weeklyFor(
        asked: Asked,
        limit: Int = AniListRecordings.limit,
    ): MockResponse {
        val title = mediaOf(json("airing_weekly")).single().jsonObject
        val ids = asked.ids.drop((asked.page - 1) * 50).take(50)
        val titles = ids.map { JsonObject(title + ("idMal" to JsonPrimitive(it))) }
        val more = asked.page * 50 < asked.ids.size
        val page = json(if (more) "more_than_one_page" else "airing_weekly")
        return reply(200, page.edited(MEDIA, JsonArray(titles)).toString(), limit)
    }

    // What an id unknown to AniList is answered with, alone or with others unknown too.
    private fun unknown(asked: Asked): MockResponse = reply(200, AniListRecordings.body("unknown_alone"))

    // M3-01 did not follow this case's pages of episodes: each recorded entry again, its schedule at an end.
    private fun laterPageOf(asked: Asked): MockResponse {
        val page = json("more_than_one_page")
        val titles =
            (mediaOf(page) + mediaOf(json("more_than_one_page_2")))
                .filter { it.jsonObject["idMal"]!!.jsonPrimitive.int in asked.ids }
                .map {
                    JsonObject(
                        it.jsonObject + ("airingSchedule" to json("unknown_among_others").let(::mediaOf)[1].jsonObject["airingSchedule"]!!),
                    )
                }
        val lastPage = json("unknown_alone")["data"]!!.jsonObject["Page"]!!.jsonObject["pageInfo"]
        return reply(200, page.edited(MEDIA, JsonArray(titles)).edited(listOf("data", "Page", "pageInfo"), lastPage).toString())
    }

    private companion object {
        val MEDIA = listOf("data", "Page", "media")

        fun mediaOf(page: JsonObject) = page["data"]!!.jsonObject["Page"]!!.jsonObject["media"]!!.jsonArray
    }
}

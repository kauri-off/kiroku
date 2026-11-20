package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.RecheckedAt
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.RecordedAt
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.idsOf
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.json
import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.pagesOf
import io.github.kauri_off.kiroku.data.anilist.AniListServer.Companion.reply
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.anime.recordedTitle
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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

    // The one entry of [id] that M3-01 recorded in [file].
    private fun recordedEntry(
        file: String,
        id: Int,
    ): AniListEntry = aniListPage(AniListRecordings.body(file)) {}!!.entries.single { it.malId == id }

    private fun whole(
        case: String,
        id: Int,
        total: Int?,
        index: Int = 0,
        doubtedOver: Int? = null,
    ): Outcome<FetchedAiring?> = Outcome.Success(FetchedAiring(AiringSchedule(recordedEpisodes(case, id, index), total, doubtedOver)))

    // 51039's [index]-th entry with every page of its schedule.
    private fun recordedPair(index: Int): AniListEntry =
        aniListPage(AniListRecordings.body("totals_differ_by_more")) {}!!
            .entries[index]
            .copy(episodes = recordedEpisodes("totals_differ_by_more", 51039, index), moreEpisodes = false)

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
    fun `a title is handed over whole as soon as its last page is read, before the pages of a longer schedule asked with it`() =
        runTest {
            val long = pagesOf("hundreds_of_episodes")
            aniList.answer = { asked ->
                if (asked.ids.size == 2) {
                    val both = mediaOf(json("airing_weekly")) + mediaOf(json(long.first()))
                    reply(200, json("airing_weekly").edited(MEDIA, JsonArray(both)).toString())
                } else {
                    aniList.recorded(asked)
                }
            }
            val handed = mutableListOf<Pair<Map<AnimeId, Outcome<FetchedAiring?>>, Int>>()

            val answers =
                aniList.client(this).fetch(listOf(61987, 235).map(::titleWithId), since, emptySet()) { part ->
                    handed +=
                        part to aniList.requests.size
                }

            assertEquals(listOf(setOf(AnimeId(61987)) to 1, setOf(AnimeId(235)) to long.size), handed.map { (part, at) -> part.keys to at })
            assertEquals(answers, handed.fold(emptyMap<AnimeId, Outcome<FetchedAiring?>>()) { all, (part, _) -> all + part })
            assertEquals(whole("airing_weekly", 61987, 12), answers.of(61987))
            assertEquals(whole("hundreds_of_episodes", 235, null), answers.of(235))
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

    // The episodes still to air that the re-check's recording of [id] holds, in order.
    private fun recordedComing(
        case: String,
        id: Int,
    ): List<ScheduledEpisode> = recordedEntry(case, id).coming!!

    @Test
    fun `N3 a re-check is one request however long the schedule, a long one answered from now on and a short one whole`() =
        runTest {
            network.clock.now = RecheckedAt

            val long = aniList.client(this).fetchComing(listOf(titleWithId(235)), since).of(235)
            assertEquals(1, aniList.requests.size)
            val short = aniList.client(this).fetchComing(listOf(titleWithId(61987)), since).of(61987)
            assertEquals(2, aniList.requests.size)

            val coming = recordedComing("recheck_hundreds_of_episodes", 235)
            assertEquals(listOf(1221, 1222), coming.map { it.number })
            assertEquals(Outcome.Success(FetchedAiring(AiringSchedule(coming, null), RecheckedAt + ClockMargin.toJavaDuration())), long)
            assertEquals(
                Outcome.Success(FetchedAiring(AiringSchedule(recordedEntry("recheck_weekly", 61987).episodes, 12))),
                short,
            )
            assertEquals(listOf(true, true), aniList.asked().map { it.recheck })
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
        }

    @Test
    fun `A9 a re-check's answer is whole from a minute past the later of the device's clock and the one AniList answered by`() =
        runTest {
            network.clock.now = RecheckedAt
            for (aniListAhead in listOf(5L, -5L)) {
                val aniListNow = RecheckedAt.plus(Duration.ofMinutes(aniListAhead))
                aniList.answer = {
                    val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(aniListNow.atOffset(ZoneOffset.UTC))
                    reply(200, AniListRecordings.body("recheck_hundreds_of_episodes"), headers = mapOf("Date" to date))
                }
                advanceTimeBy(1.minutes)

                val answer = aniList.client(this).fetchComing(listOf(titleWithId(235)), since).of(235)

                val from = maxOf(aniListNow, RecheckedAt) + ClockMargin.toJavaDuration()
                assertEquals("AniList ahead by $aniListAhead minutes", from, (answer as Outcome.Success).value!!.completeFrom)
            }
        }

    @Test
    fun `N3 a long title the re-check's one request cannot settle is asked whole, of two entries or with more to air than a page`() =
        runTest {
            val recheck = json("recheck_hundreds_of_episodes")
            val media = mediaOf(recheck).single().jsonObject
            val moreComing = media.edited(listOf(AniListSchedule.COMING, "pageInfo", "hasNextPage"), JsonPrimitive(true))
            for (answered in listOf(listOf(media, media), listOf(moreComing))) {
                aniList.requests.clear()
                aniList.answer = { asked ->
                    if (asked.recheck) reply(200, recheck.edited(MEDIA, JsonArray(answered)).toString()) else aniList.recorded(asked)
                }
                advanceTimeBy(1.minutes)

                val answer = aniList.client(this).fetchComing(listOf(titleWithId(235)), since).of(235)

                assertEquals(whole("hundreds_of_episodes", 235, null), answer)
                assertEquals(1 + pagesOf("hundreds_of_episodes").size, aniList.requests.size)
                assertEquals(listOf(true) + List(pagesOf("hundreds_of_episodes").size) { false }, aniList.asked().map { it.recheck })
            }
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
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
    fun `of two entries with one MAL id the one with the highest aired episode is chosen, every page of both followed`() =
        runTest {
            assertEquals(whole("totals_differ_by_more", 51039, 62, index = 1, doubtedOver = 52), fetch(listOf(51039)).of(51039))
            assertEquals(3, aniList.requests.size)
            assertEquals(emptyList<Asked>(), aniList.unrecorded)
        }

    @Test
    fun `a pair whose first entry ends sooner still has the second followed to its last page`() =
        runTest {
            aniList.answer = { asked ->
                val file = AniListRecordings.byRequest.getValue(asked)
                val page = json(file)
                reply(200, page.edited(MEDIA, JsonArray(mediaOf(page).reversed())).toString())
            }

            assertEquals(whole("totals_differ_by_more", 51039, 62, index = 1, doubtedOver = 52), fetch(listOf(51039)).of(51039))
            assertEquals(3, aniList.requests.size)
        }

    @Test
    fun `the rule for two entries, on the recorded pair, before either aired and without times`() {
        val pair = aniListPage(AniListRecordings.body("totals_differ_by_more")) {}!!.entries
        val secondStarts = pair[1].episodes.first().airsAt
        val untimed =
            listOf("releasing_without_times", "not_yet_aired_without_time").map {
                aniListPage(AniListRecordings.body(it)) {}!!.entries.single()
            }

        assertEquals(62, chosen(pair, RecordedAt).total)
        assertEquals(62, chosen(pair.reversed(), RecordedAt).total)
        assertEquals(52, chosen(pair, secondStarts.minusSeconds(1)).total)
        assertEquals(52, chosen(pair.reversed(), Instant.parse("2022-01-01T00:00:00Z")).total)
        assertEquals("RELEASING", untimed[0].status)
        assertEquals(untimed[0], chosen(untimed.reversed(), RecordedAt))
        val alike = untimed.map { it.copy(status = "FINISHED") }
        assertEquals(alike[0], chosen(alike, RecordedAt))
        assertEquals(alike[1], chosen(alike.reversed(), RecordedAt))
    }

    @Test
    fun `a special that airs after the series' latest episode does not take the series' place, nor lower its count`() {
        val special = recordedEntry("more_than_one_page", 61469)
        val series = recordedEntry("more_than_one_page_2", 61469)
        val latest = series.episodes.filter { it.airsAt <= RecordedAt }.maxBy { it.airsAt }
        val later = special.copy(episodes = listOf(ScheduledEpisode(1, latest.airsAt.plusSeconds(3600))))
        val anime = recordedTitle("airing_on_a_break").anime

        assertEquals("FINISHED", special.status)
        for (entries in listOf(listOf(later, series), listOf(series, later))) {
            assertEquals(series, chosen(entries, RecordedAt))
            assertEquals(series.episodes, chosen(entries.map { it.copy(status = "FINISHED") }, RecordedAt).episodes)
        }
        val count = airingAt(anime, AiringSchedule(chosen(listOf(later, series), RecordedAt).episodes, null), RecordedAt).aired
        assertEquals(AiredCount.Exact(latest.number), count)
        for (entries in listOf(listOf(special, series), listOf(later, series))) {
            assertNull(doubtedOver(series, entries, RecordedAt))
            assertNull(doubtedOver(series, entries.map { it.copy(status = "FINISHED") }, RecordedAt))
        }
        // Before the series' first episode the special is the only one aired, and the series still premieres exactly.
        val premiere =
            series.episodes
                .first()
                .airsAt
                .minusSeconds(1)
        assertEquals(series, chosen(listOf(special, series), premiere))
        assertNull(doubtedOver(series, listOf(special, series), premiere))
    }

    @Test
    fun `of one title split into two entries that each number from 1, the later doubts the earlier from its first episode on`() {
        val first = recordedPair(0)
        val second = recordedEntry("more_than_one_page", 55809).copy(malId = 51039)
        val pair = listOf(first, second)
        val anime = recordedTitle("airing_on_a_break").anime
        val starts = second.episodes.first().airsAt
        assertEquals(listOf("FINISHED", "RELEASING"), pair.map { it.status })
        assertTrue(first.episodes.maxOf { it.airsAt } < starts)
        assertEquals(null, anime.malEpisodes)

        for (at in listOf(starts.minusSeconds(1), starts) + second.episodes.drop(2).map { it.airsAt } + RecordedAt) {
            for (entries in listOf(pair, pair.reversed())) {
                val entry = chosen(entries, at)
                val schedule = AiringSchedule(entry.episodes, entry.total, doubtedOver(entry, entries, at))
                val airing = airingAt(anime, schedule, at)

                assertEquals("$at", second, entry)
                assertEquals("$at", 52, schedule.doubtedOver)
                assertEquals("$at", AiredCount.Estimated(52), airing.aired)
                assertNull("$at", airing.nextEpisode)
            }
        }
        // What the count was without the doubt: exact, and from 52 down to 1 on the day the second entry began.
        assertEquals(AiredCount.Exact(1), airingAt(anime, AiringSchedule(second.episodes, second.total), starts).aired)
        // Without the second entry's status the first is chosen; the doubt is the same from the second's first episode on.
        val alike = listOf(first, second.copy(status = "FINISHED"))
        assertEquals(first, chosen(alike, starts))
        assertNull(doubtedOver(first, alike, starts.minusSeconds(1)))
        assertEquals(1, doubtedOver(first, alike, starts))
        assertEquals(AiredCount.Estimated(52), airingAt(anime, AiringSchedule(first.episodes, first.total, 1), starts).aired)
    }

    @Test
    fun `a first entry that finished without a schedule is doubted as aired up to its total, from before the second's first episode on`() {
        val first = recordedEntry("more_than_one_page", 59953).copy(malId = 55809)
        val second = recordedEntry("more_than_one_page", 55809)
        val pair = listOf(first, second)
        val anime = recordedTitle("airing_on_a_break").anime
        val starts = second.episodes.first().airsAt
        assertEquals(listOf("FINISHED", "RELEASING"), pair.map { it.status })
        assertEquals(listOf(26, 180), pair.map { it.total })
        assertEquals(emptyList<ScheduledEpisode>(), first.episodes)

        for (at in listOf(starts.minusSeconds(1), starts) + second.episodes.drop(2).map { it.airsAt } + RecordedAt) {
            for (entries in listOf(pair, pair.reversed())) {
                val entry = chosen(entries, at)
                val schedule = AiringSchedule(entry.episodes, entry.total, doubtedOver(entry, entries, at))
                val airing = airingAt(anime, schedule, at)

                assertEquals("$at", second, entry)
                assertEquals("$at", 26, schedule.doubtedOver)
                assertEquals("$at", AiredCount.Estimated(26), airing.aired)
                assertNull("$at", airing.nextEpisode)
            }
        }
        assertEquals(AiredCount.Exact(1), airingAt(anime, AiringSchedule(second.episodes, second.total), starts).aired)
        // A second entry that numbers on from the first's total is not doubted.
        val numberedOn = second.copy(episodes = second.episodes.map { it.copy(number = it.number + 26) })
        assertNull(doubtedOver(numberedOn, listOf(first, numberedOn), starts))
    }

    @Test
    fun `the recorded split title is doubted from the first episode of its later entry on, whichever entry is chosen`() {
        val pair = listOf(recordedPair(0), recordedPair(1))
        val secondStarts = pair[1].episodes.first().airsAt

        for (entries in listOf(pair, pair.reversed())) {
            assertEquals(52, doubtedOver(pair[1], entries, RecordedAt))
            assertNull(doubtedOver(pair[0], entries, secondStarts.minusSeconds(1)))
            assertEquals(24, doubtedOver(pair[0], entries, secondStarts))
        }
    }

    @Test
    fun `with more ids than a page holds every page of titles is read, and a missing id is asked again before it is unknown`() =
        runTest {
            val ids = idsOf("more_than_one_page")
            aniList.answer = { asked ->
                val file = AniListRecordings.byRequest[asked.copy(ids = asked.ids.sorted())].orEmpty()
                if ("more_than_one_page" in file) reply(200, endedAfterFirst(json(file)).toString()) else unknown(asked)
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
            assertEquals(listOf(Asked(ids, 1, 1), Asked(ids, 2, 1), Asked(unknown, 1, 1)), aniList.asked())
        }

    @Test
    fun `a failed page of titles settles no id it read, the ids are asked again and fail when that fails`() =
        runTest {
            val ids = idsOf("more_than_one_page")
            aniList.answer = { asked -> if (asked.page == 2) reply(500, "") else aniList.recorded(asked) }
            val answers = fetch(ids)

            for (id in ids) assertEquals("$id", Outcome.Failure(DataError.ServerError), answers.of(id))
            assertEquals(listOf(Asked(ids, 1, 1), Asked(ids, 2, 1), Asked(ids.take(50), 1, 1), Asked(ids.drop(50), 1, 1)), aniList.asked())
        }

    @Test
    fun `a failed page of titles settles no id it read, and the ids asked again on their own are answered whole`() =
        runTest {
            val ids = idsOf("more_than_one_page")
            val recorded = listOf("more_than_one_page", "more_than_one_page_2").flatMap { mediaOf(endedAfterFirst(json(it))) }
            aniList.answer = { asked ->
                when {
                    asked.ids == ids -> if (asked.page == 2) reply(500, "") else aniList.recorded(asked)
                    else -> pageOf(asked, recorded.filter { it.jsonObject["idMal"]!!.jsonPrimitive.int in asked.ids })
                }
            }
            val answers = fetch(ids)
            val known = recorded.map { it.jsonObject["idMal"]!!.jsonPrimitive.int }.toSet()

            assertEquals(11, (answers.of(61469) as Outcome.Success).value!!.schedule.totalEpisodes)
            for (id in ids - known) assertEquals(Outcome.Success(null), answers.of(id))
            for (id in known) assertTrue("$id ${answers.of(id)}", (answers.of(id) as? Outcome.Success)?.value != null)
            assertEquals(listOf(Asked(ids.take(50), 1, 1), Asked(ids.drop(50), 1, 1)), aniList.asked().drop(2))
        }

    @Test
    fun `a failed page of a later round fails the titles it held, never as unknown or cut short`() =
        runTest {
            val ids = (1..60).toList()
            aniList.answer = { asked ->
                when {
                    asked.schedulePage == 1 -> copiesOf(asked, "hundreds_of_episodes_6")
                    asked.page == 2 -> reply(500, "")
                    else -> copiesOf(asked, "hundreds_of_episodes_7")
                }
            }
            val answers = fetch(ids)
            val whole = recordedEpisodes("hundreds_of_episodes_6", 235) + recordedEpisodes("hundreds_of_episodes_7", 235)

            for (id in 1..50) assertEquals(Outcome.Success(FetchedAiring(AiringSchedule(whole, null))), answers.of(id))
            for (id in 51..60) assertEquals(Outcome.Failure(DataError.ServerError), answers.of(id))
            assertEquals(listOf(Asked(ids, 1, 1), Asked(ids, 2, 1), Asked(ids, 1, 2), Asked(ids, 2, 2)), aniList.asked())
        }

    @Test
    fun `a title missing from a later round's complete answer fails, it is not answered with its first page`() =
        runTest {
            aniList.answer = { asked ->
                if (asked.schedulePage == 1) {
                    copiesOf(asked, "hundreds_of_episodes_6")
                } else {
                    copiesOf(asked.copy(ids = asked.ids - 1), "hundreds_of_episodes_7")
                }
            }
            val answers = fetch(listOf(1, 2))

            assertEquals(Outcome.Failure(DataError.ServerError), answers.of(1))
            assertTrue(answers.of(2) is Outcome.Success)
        }

    @Test
    fun `a gap or a repeat in episode numbers where pages meet fails the title and is reported`() =
        runTest {
            for (first in listOf(1093, 1091)) {
                aniList.answer = { asked ->
                    val page = json(AniListRecordings.byRequest.getValue(asked))
                    val path = MEDIA + listOf("0", "airingSchedule", "nodes", "0", "episode")
                    reply(200, (if (asked.schedulePage == 2) page.edited(path, JsonPrimitive(first)) else page).toString())
                }
                network.unexpected.clear()
                advanceTimeBy(FailureCoolDown)

                assertEquals("$first", Outcome.Failure(DataError.ServerError), fetch(listOf(235)).of(235))
                assertEquals(listOf(AniListOddAnswer::class.java), network.unexpected.map { it.javaClass })
            }
            assertEquals(List(2) { listOf(1, 2) }.flatten(), aniList.asked().map { it.schedulePage })
        }

    @Test
    fun `300 ids are asked in the fewest requests, none sooner than half the limit AniList reports allows`() =
        runTest {
            aniList.answer = { copiesOf(it) }
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
            aniList.answer = { copiesOf(it, limit = 90) }
            fetch((1..100).toList())
            aniList.answer = { copiesOf(it, limit = 1000) }
            fetch((1..150).toList())

            val shortest = AniListPace.spacingFor(AniListPace.HIGHEST_LIMIT).inWholeMilliseconds
            assertEquals(4, gaps().size)
            assertTrue(gaps().toString(), gaps().all { it in shortest..shortest + 1 })
        }

    @Test
    fun `fifty ids whose page fails all fail, without a request more`() =
        runTest {
            aniList.answer = { reply(500, "") }
            val answers = fetch((1..50).toList())

            for (id in 1..50) assertEquals(Outcome.Failure(DataError.ServerError), answers.of(id))
            assertEquals(1, aniList.requests.size)
        }

    @Test
    fun `a page of episodes that comes back empty after one that promised more fails the title and is reported`() =
        runTest {
            val ended = mediaOf(json("releasing_without_times"))[0].jsonObject["airingSchedule"]!!
            aniList.answer = { asked ->
                val page = json(AniListRecordings.byRequest.getValue(asked))
                reply(200, (if (asked.schedulePage == 2) page.edited(MEDIA + listOf("0", "airingSchedule"), ended) else page).toString())
            }

            assertEquals(Outcome.Failure(DataError.ServerError), fetch(listOf(235)).of(235))
            assertEquals(listOf(1, 2), aniList.asked().map { it.schedulePage })
            assertEquals(listOf(AniListOddAnswer::class.java), network.unexpected.map { it.javaClass })
        }

    @Test
    fun `a page that fails leaves its titles not fetched and the others fetched, each request after a failure later`() =
        runTest {
            aniList.answer = { if (it.ids.size == 100 || 1 in it.ids) reply(500, "") else copiesOf(it) }
            val answers = fetch((1..100).toList())

            for (id in 1..50) assertEquals(Outcome.Failure(DataError.ServerError), answers.of(id))
            for (id in 51..100) assertTrue(answers.of(id) is Outcome.Success)
            assertEquals(listOf(100, 50, 50), aniList.asked().map { it.ids.size })
            assertTrue(gaps().toString(), gaps().all { it >= AniListPace.AfterFailure.inWholeMilliseconds })
        }

    @Test
    fun `a request that gets no answer fails its titles after a wait, and offline the call stops`() =
        runTest {
            aniList.answer = { if (1 in it.ids) dropped() else copiesOf(it) }
            val online = fetch((1..100).toList())

            assertEquals(Outcome.Failure(DataError.ServerError), online.of(1))
            assertTrue(online.of(51) is Outcome.Success)
            assertEquals(3, aniList.requests.size)
            assertTrue(gaps().toString(), gaps().all { it >= AniListPace.AfterFailure.inWholeMilliseconds })

            network.online = false
            aniList.answer = { dropped() }
            val offline = fetch((101..200).toList())

            assertEquals(Outcome.Failure(DataError.NoConnection), offline.of(101))
            assertEquals(Outcome.Failure(DataError.NoConnection), offline.of(151))
            assertEquals(4, aniList.requests.size)
        }

    @Test
    fun `a refusal stops the call, holds every request back for its wait, and leaves MAL's back-off at zero`() =
        runTest {
            aniList.answer = { if (it.page == 2) reply(429, "", headers = mapOf("Retry-After" to "120")) else copiesOf(it) }
            val answers = fetch((1..150).toList())

            for (id in listOf(1, 51, 101)) assertEquals(Outcome.Failure(DataError.Refused), answers.of(id))
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
            aniList.answer = { copiesOf(it) }
            assertEquals(Outcome.Failure(DataError.Refused), fetch(listOf(1)).of(1))
            advanceTimeBy(1.milliseconds)
            assertTrue(fetch(listOf(1)).of(1) is Outcome.Success)
        }

    @Test
    fun `an answer of AniList's does not end MAL's back-off`() =
        runTest {
            network.always(answer(429))
            network.http.call { network.things.value.read() }
            aniList.answer = { copiesOf(it) }

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
            aniList.answer = { copiesOf(it) }
            val answers = aniList.client(this).fetch(listOf(titleWithId(1), titleWithId(1).copy(id = AnimeId(1L shl 40))), since)

            assertEquals(Outcome.Failure(DataError.InvalidRequest), answers[AnimeId(1L shl 40)])
            assertTrue(answers.of(1) is Outcome.Success)
            assertEquals(listOf(listOf(1)), aniList.asked().map { it.ids })
        }

    // What an id unknown to AniList is answered with, alone or with others unknown too.
    private fun unknown(asked: Asked): MockResponse = reply(200, AniListRecordings.body("unknown_alone"))

    // M3-01 did not follow this case's pages of episodes: each recorded entry's schedule says it ends with its first page.
    private fun endedAfterFirst(page: JsonObject): JsonObject =
        mediaOf(page).indices.fold(page) { edited, index ->
            edited.edited(MEDIA + listOf("$index", "airingSchedule", "pageInfo", "hasNextPage"), JsonPrimitive(false))
        }
}

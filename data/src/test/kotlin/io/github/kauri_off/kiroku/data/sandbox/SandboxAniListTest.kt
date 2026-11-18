package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.data.anilist.AniListSchedule
import io.github.kauri_off.kiroku.data.network.TestClock
import io.github.kauri_off.kiroku.data.recorder.AniListRequests
import io.github.kauri_off.kiroku.data.recorder.answerTo
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.COMING
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.COMING_WITHOUT_TIME
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.FILM
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.FINISHED_RECENTLY
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.LONG_RUNNING
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.ON_A_BREAK
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.TWO_ENTRIES
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.UNKNOWN
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.WEEKLY
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.WITHOUT_TIMES
import io.github.kauri_off.kiroku.data.sandbox.SandboxAiring.Companion.japanDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import kotlin.concurrent.thread

// M3-07: the sandbox answers AniList's shared query on the device, in the recorded answers' shapes and with AniList's paging,
// its schedules on each title's weekly slot and agreeing with its MAL pages, laid out again at a reset.
class SandboxAniListTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val clock = TestClock(START)

    private inner class OnDevice(
        val files: File = folder.newFolder(),
    ) {
        val sandbox =
            Sandbox(
                read = { recorded("$it.200.json") },
                readTenrai = ::recordedTenrai,
                poster = ::poster,
                clock = clock,
                file = { File(files, it) },
            )
        val client = SandboxClient(sandbox.mal, clock = clock)

        // AniList's answer to the shared query, asked through the app's client by a client that keeps AniList's limit.
        fun ask(
            ids: List<Long>,
            page: Int = 1,
            schedulePage: Int = 1,
        ): JsonObject {
            clock.now += PACE
            val (status, body) = post(AniListSchedule.body(ids.map(Long::toInt), page, schedulePage))
            assertEquals(body, 200, status)
            return Json.parseToJsonElement(body).jsonObject
        }

        fun post(
            body: String,
            method: String = "POST",
        ): Pair<Int, String> {
            val request =
                Request
                    .Builder()
                    .url(AniListSchedule.ADDRESS)
                    .method(method, if (method == "POST") body.toRequestBody(JSON) else null)
                    .build()
            return client.http.callFactory
                .newCall(request)
                .execute()
                .use { it.code to it.body.string() }
        }

        // The pages of a request as a client follows them: of titles for several ids, of one title's episodes for one id.
        fun pages(ids: List<Long>): List<JsonObject> = if (ids.size == 1) schedulePages(ids.single()) else titlePages(ids)

        fun titlePages(ids: List<Long>): List<JsonObject> {
            val pages = mutableListOf(ask(ids))
            while (pages.last().hasNextPage) pages += ask(ids, page = pages.size + 1)
            return pages
        }

        fun schedulePages(id: Long): List<JsonObject> {
            val pages = mutableListOf(ask(listOf(id)))
            while (pages.last().media.any { it.scheduleInfo["hasNextPage"]!!.jsonPrimitive.boolean }) {
                pages += ask(listOf(id), schedulePage = pages.size + 1)
            }
            return pages
        }

        // Every entry AniList keeps for [ids] with its whole schedule, as of now.
        fun entries(ids: List<Long>): Map<Long, List<Entry>> {
            val listed = titlePages(ids).flatMap { it.media }
            val now = clock.instant()
            return listed.map { it.idMal }.distinct().associateWith { id ->
                val one = listed.filter { it.idMal == id }
                val nodes = if (one.any { it.scheduleInfo["hasNextPage"]!!.jsonPrimitive.boolean }) schedulePages(id) else listOf()
                one.mapIndexed { i, media ->
                    val schedule =
                        if (nodes.isEmpty()) media.nodes else nodes.flatMap { page -> page.media.filter { it.idMal == id }[i].nodes }
                    Entry(media["episodes"]!!.jsonPrimitive.intOrNull, media["status"]!!.jsonPrimitive.content, schedule, now)
                }
            }
        }

        // A new sign-in each time, as a token outlives no more than MAL's lifetime of it on the sandbox's clock.
        private fun access(): String {
            val address =
                "${SandboxMal.Hosts.signIn}authorize?response_type=code&client_id=${SandboxMal.CLIENT_ID}&state=s" +
                    "&redirect_uri=kiroku%3A%2F%2Foauth&code_challenge=$VERIFIER&code_challenge_method=plain"
            val code = checkNotNull(sandbox.mal.authorize(address)).substringAfter("code=").substringBefore('&')
            val form =
                FormBody
                    .Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", "kiroku://oauth")
                    .add("client_id", SandboxMal.CLIENT_ID)
                    .add("code_verifier", VERIFIER)
                    .build()
            val granted =
                body(
                    Request
                        .Builder()
                        .url("${SandboxMal.Hosts.signIn}token")
                        .post(form)
                        .build(),
                )
            return granted["access_token"]!!.jsonPrimitive.content
        }

        // The titles of the list the sandbox's MAL serves in Watching, Planned or On hold.
        fun tracked(): Set<Long> {
            val request =
                Request
                    .Builder()
                    .url("${SandboxMal.Hosts.api}users/@me/animelist?fields=list_status&limit=1000")
                    .header("Authorization", "Bearer ${access()}")
                    .build()
            return body(request)["data"]!!
                .jsonArray
                .map { it.jsonObject }
                .filter { it["list_status"]!!.jsonObject.text("status") in setOf("watching", "plan_to_watch", "on_hold") }
                .map { it["node"]!!.jsonObject.id!! }
                .toSet()
        }

        private fun body(request: Request): JsonObject =
            client.http.callFactory
                .newCall(request)
                .execute()
                .use { Json.parseToJsonElement(it.body.string()).jsonObject }

        // MAL's page of a title as the sandbox's MAL gives it.
        fun mal(id: Long): JsonObject {
            val request = Request.Builder().url("${SandboxMal.Hosts.api}anime/$id?fields=$MAL_FIELDS").build()
            val body =
                client.http.callFactory
                    .newCall(request)
                    .execute()
                    .use { it.body.string() }
            return Json.parseToJsonElement(body).jsonObject
        }
    }

    class Entry(
        val episodes: Int?,
        val status: String,
        val schedule: List<Aired>,
        val now: Instant,
    ) {
        val aired = schedule.filter { !it.at.isAfter(now) }
        val coming = schedule.filter { it.at.isAfter(now) }
    }

    @Test
    fun `each answer has the fields of the recorded answer of its case, with values of the same kind`() {
        val device = OnDevice()
        val cases =
            mapOf(
                "airing_weekly" to listOf(WEEKLY),
                "releasing_without_times" to listOf(WITHOUT_TIMES),
                "on_a_break" to listOf(ON_A_BREAK),
                "hundreds_of_episodes" to listOf(LONG_RUNNING),
                "not_yet_aired_with_time" to listOf(COMING),
                "not_yet_aired_without_time" to listOf(COMING_WITHOUT_TIME),
                "finished_last_month" to listOf(FINISHED_RECENTLY),
                "totals_differ_by_one" to listOf(FILM),
                "totals_differ_by_more" to listOf(TWO_ENTRIES),
                "unknown_alone" to listOf(UNKNOWN),
                "unknown_among_others" to listOf(UNKNOWN, WEEKLY, WITHOUT_TIMES),
                "more_than_one_page" to sandboxTitles.keys.sortedDescending() + NOT_THE_SANDBOX_S,
            )
        assertEquals(AniListRequests.map { it.name }.toSet(), cases.keys)
        val asked = answerTo(AniListSchedule.QUERY)
        val statuses =
            cases.keys
                .flatMap(
                    ::recordedPages,
                ).flatMap { it.jsonObject.media }
                .map { it["status"]!!.jsonPrimitive.content }
                .toSet()
        assertEquals(setOf(SandboxAiring.RELEASING, SandboxAiring.FINISHED, SandboxAiring.NOT_YET_RELEASED), statuses)
        for ((case, ids) in cases) {
            val recorded = recordedPages(case).map(::kinds)
            val answered = device.pages(ids)
            for (page in answered) {
                assertEquals(case, emptyList<String>(), asked.unasked(page))
                for (media in page.media) {
                    assertTrue("$case: ${media["status"]}", media["status"]!!.jsonPrimitive.content in statuses)
                    for (node in media.nodes) assertTrue("$case: ${node.at}", node.at > clock.now - YEARS && node.at < clock.now + YEARS)
                }
                assertTrue("$case: ${kinds(page) - recorded.flatten().toSet()}", recorded.flatten().containsAll(kinds(page)))
            }
            assertEquals(case, recorded.flatten().toSet(), answered.flatMap(::kinds).toSet())
        }
    }

    @Test
    fun `one answer holds at most 50 titles and 25 episodes of each, the nested page is every title's, and the rest follows`() {
        val device = OnDevice()
        val ids = sandboxTitles.keys.shuffled(java.util.Random(1)) + NOT_THE_SANDBOX_S
        val pages = device.pages(ids)
        assertTrue(pages.size > 1)
        for ((i, page) in pages.withIndex()) {
            assertEquals(i < pages.lastIndex, page.hasNextPage)
            assertTrue(page.media.size <= AniListSchedule.TITLES_PER_PAGE)
            if (i < pages.lastIndex) assertEquals(AniListSchedule.TITLES_PER_PAGE, page.media.size)
            for (media in page.media) assertTrue(media.nodes.size <= AniListSchedule.EPISODES_PER_PAGE)
        }
        val answered = pages.flatMap { page -> page.media.map { it.idMal } }
        assertEquals((sandboxTitles.keys.toList() - UNKNOWN + TWO_ENTRIES).sorted(), answered.sorted())
        assertTrue(answered.distinct() != ids.filter { it in answered })
        assertEquals(emptyList<JsonObject>(), device.ask(ids, page = pages.size + 1).media)

        val long = device.pages(listOf(LONG_RUNNING)).map { it.media.single() }
        assertTrue(long.size > 2)
        for ((i, media) in long.withIndex()) {
            assertEquals(i < long.lastIndex, media.scheduleInfo["hasNextPage"]!!.jsonPrimitive.boolean)
            if (i < long.lastIndex) assertEquals(AniListSchedule.EPISODES_PER_PAGE, media.nodes.size)
        }
        val schedule = long.flatMap { it.nodes }
        assertEquals(schedule.sortedBy { it.at }, schedule)
        assertEquals((schedule.first().episode..schedule.last().episode).toList(), schedule.map { it.episode })
        val beyond = device.ask(listOf(LONG_RUNNING), schedulePage = long.size + 1).media.single()
        assertEquals(emptyList<Aired>(), beyond.nodes)
        assertEquals(false, beyond.scheduleInfo["hasNextPage"]!!.jsonPrimitive.boolean)

        val single = (sandboxTitles.keys - UNKNOWN - TWO_ENTRIES).sorted()
        val fifty = device.ask(single.take(AniListSchedule.TITLES_PER_PAGE))
        assertEquals(AniListSchedule.TITLES_PER_PAGE to false, fifty.media.size to fifty.hasNextPage)
        val fiftyOne = device.ask(single.take(AniListSchedule.TITLES_PER_PAGE + 1))
        assertEquals(AniListSchedule.TITLES_PER_PAGE to true, fiftyOne.media.size to fiftyOne.hasNextPage)
        assertEquals(1, device.ask(single.take(AniListSchedule.TITLES_PER_PAGE + 1), page = 2).media.size)
        val twoPages = device.pages(listOf(ON_A_BREAK)).map { it.media.single() }
        assertEquals(
            listOf(AniListSchedule.EPISODES_PER_PAGE to true, AniListSchedule.EPISODES_PER_PAGE to false),
            twoPages.map { it.nodes.size to it.scheduleInfo["hasNextPage"]!!.jsonPrimitive.boolean },
        )

        val both = device.ask(listOf(WEEKLY, ON_A_BREAK), schedulePage = 2).media.associateBy { it.idMal }
        assertEquals(emptyList<Aired>(), both.getValue(WEEKLY).nodes)
        assertEquals(AniListSchedule.EPISODES_PER_PAGE, both.getValue(ON_A_BREAK).nodes.size)
    }

    @Test
    fun `on any date its schedules fall on the title's weekday and time, and agree with its MAL pages`() {
        for (start in STARTS) {
            clock.now = start
            val device = OnDevice()
            for (after in AFTER) {
                clock.now = start + after
                val entries = device.entries(sandboxTitles.keys.toList())
                assertTrue(UNKNOWN !in entries)
                for ((id, kept) in entries) {
                    val mal = device.mal(id)
                    val at = "$start + $after, $id"
                    for (entry in kept) {
                        val slots =
                            entry.schedule.map {
                                it.at.atZone(SandboxAiring.JAPAN).let { local ->
                                    local.dayOfWeek to
                                        local.toLocalTime()
                                }
                            }
                        assertTrue(at, slots.distinct().size <= 1)
                        val broadcast = (mal["broadcast"] as? JsonObject)?.slot
                        if (broadcast != null && slots.isNotEmpty()) assertEquals(at, broadcast.day to broadcast.time, slots.first())
                    }
                    if (id == TWO_ENTRIES) continue
                    val entry = kept.single()
                    assertEquals(at, entry.status, aniListStatus(mal.text("status")))
                    val total = mal["num_episodes"]!!.jsonPrimitive.int.takeIf { it > 0 }
                    if (total != null && entry.episodes != null) assertEquals(at, if (id == FILM) total + 1 else total, entry.episodes)
                    val first = entry.schedule.firstOrNull()
                    if (first?.episode == 1) assertTrue(at, japanDate(first.at).toString().startsWith(mal.text("start_date")!!))
                    if (entry.status == SandboxAiring.FINISHED && first != null) {
                        assertEquals(at, japanDate(entry.schedule.last().at).toString(), mal.text("end_date"))
                    }
                    val season = mal["start_season"] as? JsonObject
                    val start =
                        mal
                            .text(
                                "start_date",
                            )?.takeIf { it.length >= "yyyy-MM".length }
                            ?.let { YearMonth.parse(it.take("yyyy-MM".length)) }
                    if (id in PLANNED && season != null && start != null) {
                        assertEquals(at, start.year, season["year"]!!.jsonPrimitive.int)
                        assertEquals(at, listOf("winter", "spring", "summer", "fall")[(start.monthValue - 1) / 3], season.text("season"))
                    }
                }
            }
        }
    }

    @Test
    fun `among the titles it tracks at the start and after a reset weeks later are the cases the app must handle`() {
        val device = OnDevice()
        tracksEveryCase(device)
        clock.now += WEEK.multipliedBy(5)
        device.sandbox.reset()
        tracksEveryCase(device)
    }

    // The cases, each met by a title of the list the sandbox's MAL serves in Watching, Planned or On hold.
    private fun tracksEveryCase(device: OnDevice) {
        val tracked = device.tracked()
        val now = clock.now
        val entries = device.entries(sandboxTitles.keys.toList())

        fun weekly(entry: Entry) =
            entry.schedule.zipWithNext().all { (a, b) ->
                b.episode == a.episode + 1 &&
                    Duration.between(a.at, b.at) == WEEK
            }

        fun releasing(entry: Entry) = entry.status == SandboxAiring.RELEASING

        fun notYet(entry: Entry) = entry.status == SandboxAiring.NOT_YET_RELEASED
        val cases: Map<String, (Long, Entry) -> Boolean> =
            mapOf(
                "airing weekly" to { _, it ->
                    releasing(it) &&
                        it.schedule.firstOrNull()?.episode == 1 &&
                        it.aired.isNotEmpty() &&
                        it.coming.isNotEmpty() &&
                        weekly(it)
                },
                "without a schedule" to { _, it -> releasing(it) && it.schedule.isEmpty() },
                "on a break" to { _, it ->
                    releasing(it) &&
                        it.aired.isNotEmpty() &&
                        it.coming.isNotEmpty() &&
                        Duration.between(it.aired.last().at, it.coming.first().at) > WEEK
                },
                "numbered on from an earlier season" to { id, it ->
                    val lowest = it.schedule.firstOrNull()
                    lowest != null && lowest.episode > 1 && device.mal(id).text("start_date") == japanDate(lowest.at).toString()
                },
                "next episode within minutes" to
                    { _, it -> it.coming.firstOrNull()?.let { next -> next.at <= now + SandboxAiring.SOON_AFTER } == true },
                "not yet aired, its times known" to { _, it -> notYet(it) && it.schedule.isNotEmpty() },
                "not yet aired, no time" to { _, it -> notYet(it) && it.schedule.isEmpty() },
                "finished on both sources within the last month" to { id, it ->
                    it.status == SandboxAiring.FINISHED &&
                        it.schedule.lastOrNull()?.let { last -> last.at > now - MONTH } == true &&
                        device.mal(id).text("status") == "finished_airing"
                },
                "two entries for one MAL id" to { id, _ -> entries.getValue(id).size == 2 },
            )
        for ((case, test) in cases) {
            assertTrue("$case at $now", entries.any { (id, kept) -> id in tracked && kept.any { test(id, it) } })
        }
        assertTrue("unknown to AniList at $now", (tracked - entries.keys).isNotEmpty())
    }

    @Test
    fun `a reset lays the schedules out again as they started, and the airing outlives the process`() {
        val first = OnDevice()
        val ids = PLANNED + WITHOUT_TIMES

        fun shape(entries: Map<Long, List<Entry>>) =
            entries.mapValues { (_, kept) ->
                kept.map {
                    listOf(
                        it.status,
                        it.aired.size,
                        it.coming.size,
                        it.coming.firstOrNull()?.let { at ->
                            Duration.between(it.now, at.at)
                        },
                    )
                }
            }
        val started = first.entries(ids)
        val soon =
            started
                .getValue(SandboxAiring.SOON)
                .single()
                .coming
                .first()
                .at
        assertTrue(soon > clock.now && soon <= clock.now + SandboxAiring.SOON_AFTER)

        clock.now += WEEK.multipliedBy(3) + Duration.ofHours(2)
        val later = OnDevice(first.files).entries(ids)

        // Every schedule but the two AniList changes as time passes.
        fun unchanged(entries: Map<Long, List<Entry>>) =
            (entries - WEEKLY - SandboxAiring.NUMBERED_ON).mapValues { (_, kept) -> kept.map { it.schedule } }
        assertEquals(unchanged(started), unchanged(later))
        assertEquals(
            started
                .getValue(LONG_RUNNING)
                .single()
                .aired.size + 3,
            later
                .getValue(LONG_RUNNING)
                .single()
                .aired.size,
        )

        first.sandbox.reset()
        val reset = first.entries(ids)
        val again = OnDevice(first.files).entries(ids)
        assertEquals(reset.mapValues { (_, kept) -> kept.map { it.schedule } }, again.mapValues { (_, kept) -> kept.map { it.schedule } })
        val before = shape(started)
        val after = shape(reset)
        assertEquals(before.keys, after.keys)
        for (id in before.keys) {
            // The first coming episode is as far ahead as at the start, give or take the week of its own slot.
            fun dropTime(shape: List<List<Any?>>) = shape.map { it.take(3) }
            assertEquals("$id", dropTime(before.getValue(id)), dropTime(after.getValue(id)))
        }
        val next =
            reset
                .getValue(SandboxAiring.SOON)
                .single()
                .coming
                .first()
                .at
        assertTrue(next > clock.now && next <= clock.now + SandboxAiring.SOON_AFTER)
    }

    @Test
    fun `as time passes AniList moves one title's next episode a week later and removes another's last, and MAL agrees`() {
        val device = OnDevice()
        val numbered = SandboxAiring.NUMBERED_ON

        fun entry(id: Long) = device.entries(listOf(id)).getValue(id).single()
        val weekly = entry(WEEKLY)
        val season = entry(numbered)

        val before = weekly.schedule.single { it.episode == SandboxAiring.MOVED - 1 }.at
        clock.now = before - Duration.ofMinutes(1)
        assertEquals(weekly.schedule, entry(WEEKLY).schedule)
        clock.now = before + Duration.ofMinutes(1)
        val moved = entry(WEEKLY)
        assertEquals(SandboxAiring.MOVED, moved.coming.first().episode)
        assertEquals(weekly.schedule.map { if (it.episode >= SandboxAiring.MOVED) it.copy(at = it.at + WEEK) else it }, moved.schedule)

        clock.now = START + SandboxAiring.REMOVED_AFTER - Duration.ofHours(1)
        assertEquals(season.schedule to 12, entry(numbered).let { it.schedule to it.episodes })
        clock.now = START + SandboxAiring.REMOVED_AFTER + Duration.ofHours(1)
        val removed = entry(numbered)
        assertTrue(season.schedule.last().at > clock.now)
        assertEquals(season.schedule.dropLast(1) to 11, removed.schedule to removed.episodes)

        // Each title finishes at its new last episode on both sources, and not at its old one.
        for ((id, old, new) in listOf(
            Triple(WEEKLY, weekly.schedule.last(), moved.schedule.last()),
            Triple(numbered, season.schedule.last(), removed.schedule.last()),
        )) {
            clock.now = minOf(old.at, new.at) + Duration.ofHours(1)
            val first = if (id == WEEKLY) "currently_airing" else "finished_airing"
            assertEquals("$id", first, device.mal(id).text("status"))
            clock.now = maxOf(old.at, new.at) + Duration.ofHours(1)
            val mal = device.mal(id)
            assertEquals("$id", "finished_airing" to japanDate(new.at).toString(), mal.text("status") to mal.text("end_date"))
            assertEquals("$id", SandboxAiring.FINISHED, entry(id).status)
        }
    }

    @Test
    fun `an id it does not know is answered as AniList answers it, and another query, no user agent and too many requests are refused`() {
        val device = OnDevice()
        for (ids in listOf(listOf(UNKNOWN), listOf(NOT_THE_SANDBOX_S))) {
            assertEquals(Json.parseToJsonElement(recordedPages("unknown_alone").single().toString()), device.ask(ids))
        }
        assertEquals(400, device.post("""{"query":"{Viewer{id}}"}""").first)
        assertEquals(400, device.post("", method = "GET").first)
        val unnamed =
            Request
                .Builder()
                .url(AniListSchedule.ADDRESS)
                .post(AniListSchedule.body(listOf(WEEKLY.toInt())).toRequestBody(JSON))
                .build()
        assertEquals(
            403,
            device.sandbox.aniList
                .answer(unnamed)
                .status,
        )

        clock.now += Duration.ofMinutes(2)
        val body = AniListSchedule.body(listOf(WEEKLY.toInt()))
        repeat(SandboxAniList.LIMIT) { assertEquals(200, device.post(body).first) }
        val request =
            Request
                .Builder()
                .url(AniListSchedule.ADDRESS)
                .post(body.toRequestBody(JSON))
                .build()
        val refused =
            device.client.http.callFactory
                .newCall(request)
                .execute()
        assertEquals(429, refused.code)
        assertEquals("60", refused.header("Retry-After"))
        assertEquals("0", refused.header("X-RateLimit-Remaining"))
        refused.close()
        clock.now += Duration.ofMinutes(1)
        assertEquals(200, device.post(body).first)
        assertTrue(
            device.sandbox.aniList.asked
                .contains(listOf(WEEKLY)),
        )
        assertTrue("POST graphql.anilist.co/" in device.sandbox.mal.asked)
    }

    @Test
    fun `failing, it answers with a server error, and not answering, it holds a request until it answers again or the client gives up`() {
        val device = OnDevice()
        val aniList = device.sandbox.aniList
        val body = AniListSchedule.body(listOf(WEEKLY.toInt()))
        aniList.fault = SandboxAniListFault.Fails
        val (status, text) = device.post(body)
        assertEquals(500, status)
        assertEquals(JsonNull, Json.parseToJsonElement(text).jsonObject["data"])
        assertEquals(listOf(WEEKLY), aniList.asked.last())

        aniList.fault = SandboxAniListFault.NoAnswer
        val client =
            OkHttpClient
                .Builder()
                .readTimeout(HELD)
                .addInterceptor(device.sandbox.mal)
                .build()

        fun call(client: OkHttpClient) =
            client.newCall(
                Request
                    .Builder()
                    .url(AniListSchedule.ADDRESS)
                    .header("User-Agent", "Kiroku/1.2.3")
                    .post(body.toRequestBody(JSON))
                    .build(),
            )
        val began = System.nanoTime()
        assertThrows(SocketTimeoutException::class.java) { call(client).execute() }
        assertTrue(Duration.ofNanos(System.nanoTime() - began) >= HELD)

        val cancelled = call(client.newBuilder().readTimeout(Duration.ofMinutes(1)).build())
        thread {
            Thread.sleep(HELD.toMillis())
            cancelled.cancel()
        }
        val asked = System.nanoTime()
        assertThrows(IOException::class.java) { cancelled.execute() }
        assertTrue(Duration.ofNanos(System.nanoTime() - asked) < Duration.ofSeconds(10))

        val answered = call(client.newBuilder().readTimeout(Duration.ofMinutes(1)).build())
        thread {
            Thread.sleep(HELD.toMillis())
            aniList.fault = SandboxAniListFault.None
        }
        assertEquals(200, answered.execute().use { it.code })
        assertEquals(listOf(WEEKLY), aniList.asked.last())
    }

    @Test
    fun `a reset lays the schedules out again before AniList answers again, so a request held until then answers from them`() {
        val device = OnDevice()
        device.sandbox.aniList.fault = SandboxAniListFault.NoAnswer
        var answer: Pair<Int, String>? = null
        val held = thread { answer = device.post(AniListSchedule.body(listOf(SandboxAiring.SOON.toInt()))) }
        while ("POST graphql.anilist.co/" !in device.sandbox.mal.asked) Thread.sleep(POLL)
        clock.now += Duration.ofDays(1)

        device.sandbox.reset()
        held.join()
        assertEquals(SandboxAniListFault.None, device.sandbox.aniList.fault)
        val (status, body) = checkNotNull(answer)
        assertEquals(200, status)
        val next =
            Json
                .parseToJsonElement(body)
                .jsonObject.media
                .single()
                .nodes
                .map { it.at }
                .first { it > clock.now }
        assertTrue("$next", next <= clock.now + SandboxAiring.SOON_AFTER)
    }

    @Test
    fun `a planted episode is AniList's at its time in every answer and in a new process, and at its new time once postponed`() {
        val first = OnDevice()

        fun soon(device: OnDevice = first) = device.entries(listOf(SandboxAiring.SOON)).getValue(SandboxAiring.SOON).single()
        val laid = soon()
        val next = laid.coming.first()
        val slot = first.mal(SandboxAiring.SOON)["broadcast"]

        val at = clock.now + Duration.ofMinutes(20)
        val planted = checkNotNull(first.sandbox.plant(at))
        assertEquals(Planted(next.episode, at), planted)
        val expected =
            laid.schedule.map {
                if (it.episode <
                    next.episode
                ) {
                    it
                } else {
                    Aired(it.episode, at + WEEK.multipliedBy((it.episode - next.episode).toLong()))
                }
            }
        repeat(2) { assertEquals(expected, soon().schedule) }
        assertEquals(expected, soon(OnDevice(first.files)).schedule)
        assertEquals(listOf(planted), OnDevice(first.files).sandbox.planted)
        assertEquals(slot, first.mal(SandboxAiring.SOON)["broadcast"])

        val postponed = checkNotNull(first.sandbox.postpone(Duration.ofMinutes(15)))
        assertEquals(Planted(next.episode, at, at + Duration.ofMinutes(15)), postponed)
        val later = OnDevice(first.files)
        assertEquals(
            expected.map {
                if (it.episode <
                    next.episode
                ) {
                    it
                } else {
                    it.copy(at = it.at + Duration.ofMinutes(15))
                }
            },
            soon(later).schedule,
        )

        // Once it has aired, it is not postponed, and the next plant is the episode after it.
        clock.now = postponed.at + Duration.ofMinutes(1)
        assertEquals(null, later.sandbox.postpone(Duration.ofMinutes(15)))
        val again = checkNotNull(later.sandbox.plant(clock.now + Duration.ofMinutes(5)))
        assertEquals(next.episode + 1, again.episode)
        assertEquals(postponed.at, soon(OnDevice(first.files)).schedule.single { it.episode == next.episode }.at)
        assertEquals(again.at, soon(OnDevice(first.files)).coming.first().at)
        assertEquals(null, later.sandbox.plant(clock.now))

        later.sandbox.reset()
        assertEquals(emptyList<Planted>(), OnDevice(first.files).sandbox.planted)
        clock.now += YEARS
        assertEquals(null, OnDevice(first.files).sandbox.plant(clock.now + Duration.ofMinutes(5)))
    }

    private companion object {
        const val POLL = 10L
        val HELD: Duration = Duration.ofMillis(300)
        val START: Instant = Instant.parse("2026-11-16T05:47:00Z")
        val WEEK: Duration = Duration.ofDays(7)
        val MONTH: Duration = Duration.ofDays(31)
        val YEARS: Duration = Duration.ofDays(3653)
        const val VERIFIER = "sandbox-verifier-0123456789-0123456789-0123456789"

        // A client within AniList's limit asks no more often than this.
        val PACE: Duration = Duration.ofSeconds(60L / SandboxAniList.LIMIT)
        const val NOT_THE_SANDBOX_S = 9874L
        val JSON = "application/json".toMediaType()
        const val MAL_FIELDS = "status,num_episodes,start_date,end_date,start_season,broadcast"

        // Starts on different weekdays, hours and years, none within minutes of a weekly slot.
        val STARTS: List<Instant> =
            listOf(
                "2026-11-16T05:47:00Z",
                "2026-12-31T23:30:00Z",
                "2027-03-05T13:58:20Z",
                "2028-02-29T03:12:00Z",
                "2029-07-14T09:05:00Z",
                "2031-10-01T17:33:00Z",
            ).map(Instant::parse)
        val AFTER: List<Duration> = listOf(Duration.ZERO, Duration.ofDays(9).plusHours(3), Duration.ofDays(40), Duration.ofDays(220))

        val PLANNED =
            listOf(
                WEEKLY,
                SandboxAiring.SOON,
                ON_A_BREAK,
                LONG_RUNNING,
                TWO_ENTRIES,
                SandboxAiring.NUMBERED_ON,
                COMING,
                COMING_WITHOUT_TIME,
                FILM,
                FINISHED_RECENTLY,
            )

        fun recordedPages(case: String): List<JsonElement> =
            generateSequence(1) { it + 1 }
                .map { page ->
                    SandboxAniListTest::class.java.getResource("/anilist/recorded/$case${if (page == 1) "" else "_$page"}.200.json")
                }.takeWhile { it != null }
                .map { Json.parseToJsonElement(it!!.readText()) }
                .toList()

        // Every field of an answer as its path and the kind of its value; an empty array is a kind of its own.
        fun kinds(
            element: JsonElement,
            path: String = "",
        ): Set<String> =
            when (element) {
                is JsonObject -> element.flatMap { (key, value) -> kinds(value, "$path.$key") }.toSet()
                is JsonArray -> if (element.isEmpty()) setOf("$path:[]") else element.flatMap { kinds(it, "$path[]") }.toSet()
                is JsonNull -> setOf("$path:null")
                is JsonPrimitive ->
                    setOf(
                        when {
                            element.isString -> "$path:string"
                            element.booleanOrNull != null -> "$path:boolean"
                            element.longOrNull != null -> "$path:integer"
                            else -> "$path:number"
                        },
                    )
            }

        fun aniListStatus(mal: String?) =
            when (mal) {
                "finished_airing" -> SandboxAiring.FINISHED
                "not_yet_aired" -> SandboxAiring.NOT_YET_RELEASED
                else -> SandboxAiring.RELEASING
            }

        val JsonObject.page get() = this["data"]!!.jsonObject["Page"]!!.jsonObject
        val JsonObject.hasNextPage get() = page["pageInfo"]!!.jsonObject["hasNextPage"]!!.jsonPrimitive.boolean
        val JsonObject.media get() = page["media"]!!.jsonArray.map { it.jsonObject }
        val JsonObject.idMal get() = this["idMal"]!!.jsonPrimitive.long
        val JsonObject.scheduleInfo get() = this["airingSchedule"]!!.jsonObject["pageInfo"]!!.jsonObject
        val JsonObject.nodes
            get() =
                this["airingSchedule"]!!.jsonObject["nodes"]!!.jsonArray.map {
                    Aired(
                        it.jsonObject["episode"]!!.jsonPrimitive.int,
                        Instant.ofEpochSecond(it.jsonObject["airingAt"]!!.jsonPrimitive.long),
                    )
                }
    }
}

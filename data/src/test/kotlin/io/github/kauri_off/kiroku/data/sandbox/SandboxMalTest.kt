package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.SentValues
import io.github.kauri_off.kiroku.core.list.sent
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.data.list.MalRemoteList
import io.github.kauri_off.kiroku.data.network.MalFields
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

// The sandbox on made-up values through the app's client: MAL's recorded rules, and the committed answers' shapes.
@RunWith(RobolectricTestRunner::class)
class SandboxMalTest {
    @get:Rule
    internal val sandbox = SandboxFixture()

    private val client by lazy { MalRemoteList(sandbox.client.http, sandbox.session) }

    private fun parse(text: String) = Json.parseToJsonElement(text)

    private val finished: Anime by lazy { runBlocking { anime(FINISHED) } }

    private suspend fun anime(id: Long): Anime {
        sandbox.list.write(id, mapOf("status" to "plan_to_watch"))
        val entry = (client.fetchEntry(AnimeId(id)) as Outcome.Success).value!!
        sandbox.list.remove(id)
        return entry.anime
    }

    private fun put(values: ListValues?): Outcome<ListEntry?> = put(values?.sent)

    private fun put(values: SentValues?): Outcome<ListEntry?> = runBlocking { client.put(finished, values) }

    private fun ListValues.carrying(vararg fields: ListField) = SentValues(this, fields.toSet())

    private fun read(): ListEntry? = runBlocking { (client.fetchEntry(finished.id) as Outcome.Success).value }

    private val planned = ListValues(ListStatus.Planned, 0, null, null, null)

    private fun changed(form: Map<String, String>): Pair<Int, String> = sandbox.ask("PATCH", "anime/$FINISHED/my_list_status", form)

    @Test
    fun `a write answers with the entry as a read of it gives it, and is its last change`() {
        val answer = (put(planned.copy(watchedEpisodes = 3, score = 7)) as Outcome.Success).value!!
        assertEquals(answer, read())
        assertEquals(ListStatus.Planned, answer.values.status)
        assertEquals(3, answer.values.watchedEpisodes)
        assertEquals(sandbox.clock.now, answer.updatedAt)
    }

    // W7 is the app's: MAL changes no value because another changed.
    @Test
    fun `MAL changes nothing on its own, neither at the first episode nor at the total nor at Completed`() {
        put(planned)
        put(planned.copy(watchedEpisodes = 1))
        assertEquals(planned.copy(watchedEpisodes = 1), read()!!.values)
        put(planned.copy(watchedEpisodes = TOTAL))
        assertEquals(planned.copy(watchedEpisodes = TOTAL), read()!!.values)
        put(planned.copy(status = ListStatus.Completed, watchedEpisodes = 3))
        assertEquals(planned.copy(status = ListStatus.Completed, watchedEpisodes = 3), read()!!.values)
    }

    @Test
    fun `a count above a known total is answered as done and ends at the total, and a title with no total keeps any count`() {
        put(planned.copy(watchedEpisodes = TOTAL - 1))
        assertEquals(TOTAL, (put(planned.copy(watchedEpisodes = TOTAL + 5)) as Outcome.Success).value!!.values.watchedEpisodes)
        assertEquals(TOTAL, read()!!.values.watchedEpisodes)

        val endless = runBlocking { anime(NO_TOTAL) }
        val answer = runBlocking { client.put(endless, planned.copy(watchedEpisodes = 5000).sent) }
        assertEquals(5000, (answer as Outcome.Success).value!!.values.watchedEpisodes)
    }

    @Test
    fun `a write keeps every value it does not carry, a status too, and a new entry without a status is refused`() {
        val counted = planned.copy(watchedEpisodes = 4)
        assertEquals(Outcome.Failure(DataError.InvalidRequest), put(counted.carrying(ListField.WatchedEpisodes)))
        assertNull(read())
        val held = planned.copy(status = ListStatus.OnHold, score = 6, startDate = day)
        put(held)
        put(counted.carrying(ListField.WatchedEpisodes))
        assertEquals(held.copy(watchedEpisodes = 4), read()!!.values)
        put(planned.carrying(ListField.Score, ListField.StartDate))
        assertEquals(held.copy(watchedEpisodes = 4, score = null, startDate = null), read()!!.values)
    }

    @Test
    fun `an empty date and MAL's zero date clear a date, and a date keeps the precision it was sent with`() {
        put(planned.copy(startDate = day, finishDate = day))
        assertEquals(200, changed(mapOf("start_date" to "")).first)
        assertEquals(200, changed(mapOf("finish_date" to "0000-00-00")).first)
        assertEquals(planned, read()!!.values)
        put(planned.copy(startDate = PartialDate.Month(YearMonth.of(2001, 4)), finishDate = PartialDate.Day(LocalDate.of(2100, 1, 1))))
        val values = read()!!.values
        assertEquals(PartialDate.Month(YearMonth.of(2001, 4)), values.startDate)
        assertEquals(PartialDate.Day(LocalDate.of(2100, 1, 1)), values.finishDate)
        // A finish before the start is kept as sent.
        put(planned.copy(startDate = day, finishDate = PartialDate.Day(LocalDate.of(2000, 1, 1))))
        assertEquals(PartialDate.Day(LocalDate.of(2000, 1, 1)), read()!!.values.finishDate)
    }

    @Test
    fun `a score above 10 and a status MAL does not know are refused with 400, and the entry is as it was, its time included`() {
        put(planned.copy(score = 5))
        val before = read()
        sandbox.clock.now = sandbox.clock.now.plusSeconds(60)
        for (form in listOf(mapOf("score" to "11"), mapOf("status" to "kiroku"))) {
            val (status, body) = changed(form)
            assertEquals(400, status)
            assertEquals("{error:string,message:string}", shape(parse(body)))
        }
        assertEquals(before, read())
    }

    @Test
    fun `a removal is answered with an empty array, also of a title that is not on the list, and a title MAL does not know is not found`() {
        put(planned)
        assertEquals(200 to "[]", sandbox.ask("DELETE", "anime/$FINISHED/my_list_status"))
        assertNull(read())
        assertEquals(200 to "[]", sandbox.ask("DELETE", "anime/$FINISHED/my_list_status"))
        assertEquals(Outcome.Success(null), put(null as SentValues?))
        assertEquals(404, sandbox.ask("DELETE", "anime/999999999/my_list_status").first)
    }

    // Shapes of MAL's own answers that need no sign-in, or that MAL's reference prints.
    @Test
    fun `MAL's committed answers to a title it does not know and to a token it rejects are the sandbox's`() {
        val unknown = sandbox.ask("GET", "anime/999999999?fields=id")
        assertEquals(404 to parse(recorded("title_unknown.404.json")), unknown.first to parse(unknown.second))
        val rejected = sandbox.ask("GET", "users/@me/animelist?fields=list_status", bearer = "not-a-token")
        assertEquals(401 to parse(recorded("token_invalid.401.json")), rejected.first to parse(rejected.second))
    }

    @Test
    fun `a title's own page is MAL's recorded answer, with the stand-in's pictures in place of MAL's`() {
        val (status, body) = sandbox.ask("GET", "anime/$FINISHED?fields=${MalFields.DETAILS}")
        assertEquals(200, status)
        val page = parse(body).jsonObject
        val answer = parse(recorded("title_finished.200.json")).jsonObject
        assertEquals(shape(answer), shape(page))
        assertEquals(
            answer - "main_picture" - "related_anime" - "recommendations",
            page - "main_picture" - "related_anime" - "recommendations",
        )
        assertEquals(poster(FINISHED), page["main_picture"]!!.jsonObject["medium"]!!.jsonPrimitive.content)
        assertFalse(body.contains("myanimelist.net/images"))
    }

    @Test
    fun `a list page and each title's node have MAL's shapes, and the next page starts where the page ended`() {
        val public = parse(recorded("list_public_1.200.json")).jsonObject
        val ranked = parse(recorded("ranking_airing.200.json")).jsonObject
        val listed = ranked["data"]!!.jsonArray.map { it.jsonObject["node"]!!.jsonObject }
        for (node in listed) {
            put(
                node.id!!,
                mapOf(
                    "status" to "completed",
                    "num_watched_episodes" to "1",
                    "start_date" to "2001-01-01",
                    "finish_date" to "2001-02-01",
                ),
            )
        }
        val asked = "users/@me/animelist?fields=${MalFields.LIST_ENTRY}&nsfw=true&sort=anime_title&limit=4"
        val first = parse(sandbox.ask("GET", "$asked&offset=0").second).jsonObject
        assertEquals(setOf("data", "paging"), first.keys)
        val next = first["paging"]!!.jsonObject["next"]!!.jsonPrimitive.content
        assertTrue(next, next.contains("offset=4"))
        val dated = public["data"]!!.jsonArray.first { "finish_date" in it.jsonObject["list_status"]!!.jsonObject }
        assertEquals(shape(dated.jsonObject["list_status"]!!), shape(first["data"]!!.jsonArray.first().jsonObject["list_status"]!!))

        var offset = 0
        val nodes = mutableListOf<JsonObject>()
        while (true) {
            val page = parse(sandbox.ask("GET", "$asked&offset=$offset").second).jsonObject
            nodes += page["data"]!!.jsonArray.map { it.jsonObject["node"]!!.jsonObject }
            offset =
                page["paging"]!!
                    .jsonObject["next"]
                    ?.jsonPrimitive
                    ?.content
                    ?.substringAfter("offset=")
                    ?.substringBefore('&')
                    ?.toInt()
                    ?: break
        }
        assertEquals(listed.map { it.id }.toSet(), nodes.map { it.id }.toSet())
        for (node in nodes) assertEquals(shape(listed.first { it.id == node.id }), shape(node))
    }

    private fun put(
        id: Long,
        form: Map<String, String>,
    ) = assertTrue(sandbox.list.write(id, form) is Written.Done)

    @Test
    fun `a sign-in's code is granted the tokens of MAL's printed answer once, for its own verifier`() {
        val address =
            "${SandboxMal.Hosts.signIn}authorize?response_type=code&client_id=${SandboxMal.CLIENT_ID}&state=s1" +
                "&redirect_uri=kiroku%3A%2F%2Foauth&code_challenge=verifier-0123&code_challenge_method=plain"
        val redirect = checkNotNull(sandbox.mal.authorize(address))
        assertTrue(redirect, redirect.startsWith("kiroku://oauth?code=") && redirect.endsWith("&state=s1"))
        val code = redirect.substringAfter("code=").substringBefore('&')
        val grant =
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to "kiroku://oauth",
                "client_id" to SandboxMal.CLIENT_ID,
            )

        assertEquals(400, token(grant + ("code_verifier" to "another")).first)
        val code2 = checkNotNull(sandbox.mal.authorize(address)).substringAfter("code=").substringBefore('&')
        val (status, body) = token(grant + ("code" to code2) + ("code_verifier" to "verifier-0123"))
        assertEquals(200, status)
        assertEquals(shape(parse(printed("token.json"))), shape(parse(body)))
        val access = parse(body).jsonObject["access_token"]!!.jsonPrimitive.content
        assertEquals(200, sandbox.ask("GET", "users/@me?fields=id", bearer = access).first)
        assertEquals(400, token(grant + ("code" to code2) + ("code_verifier" to "verifier-0123")).first)
    }

    private fun token(form: Map<String, String>) = sandbox.ask("POST", "token", form, bearer = null, host = SandboxMal.Hosts.signIn)

    @Test
    fun `a refresh grants a new pair and revokes the old one, and a rejected refresh is MAL's committed answer`() {
        val refresh =
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to SandboxMal.CLIENT_ID,
                "refresh_token" to sandbox.signedIn.refresh,
            )
        val (status, body) = token(refresh)
        assertEquals(200, status)
        assertEquals(401, sandbox.ask("GET", "users/@me?fields=id").first)
        assertEquals(
            200,
            sandbox.ask("GET", "users/@me?fields=id", bearer = parse(body).jsonObject["access_token"]!!.jsonPrimitive.content).first,
        )
        val again = token(refresh)
        assertEquals(401 to parse(recorded("refresh_invalid.401.json")), again.first to parse(again.second))
    }

    // S6 through the sandbox: the request is rejected, the session's one refresh is rejected too, and it has expired.
    @Test
    fun `while the sandbox rejects every token, the next request ends the session through a rejected refresh`() =
        runBlocking {
            assertTrue(client.fetch() is Outcome.Success)
            sandbox.mal.rejecting = true
            assertEquals(Outcome.Failure(DataError.SessionExpired), client.fetch())
            assertTrue(sandbox.session.observeSession().first() is Session.Expired)
            assertEquals(
                listOf("GET sandbox.invalid/v2/users/@me/animelist", "POST sandbox.invalid/v1/oauth2/token"),
                sandbox.mal.asked
                    .drop(1),
            )
        }

    // S5 on the sandbox's clock: the token is rejected once, the session's one refresh is granted, the request is asked again.
    @Test
    fun `an access token is rejected after its lifetime or when expired now, and one refresh and one retry follow`() =
        runBlocking {
            for (expire in listOf({ sandbox.clock.now += SandboxTokens.ACCESS_LIFETIME }, { sandbox.mal.expireAccess() })) {
                assertTrue(client.fetch() is Outcome.Success)
                val before = sandbox.mal.asked.size
                expire()
                assertTrue(client.fetch() is Outcome.Success)
                assertEquals(listOf(LIST, TOKEN, LIST), sandbox.mal.asked.drop(before))
                assertTrue(sandbox.session.observeSession().first() is Session.SignedIn)
            }
        }

    @Test
    fun `a refresh token is rejected after its month, and the session then expires`() =
        runBlocking {
            sandbox.clock.now += SandboxTokens.REFRESH_LIFETIME
            assertEquals(Outcome.Failure(DataError.SessionExpired), client.fetch())
            assertTrue(sandbox.session.observeSession().first() is Session.Expired)
        }

    @Test
    fun `offline, refusing and failing writes, and slow answers reach the client as MAL's would`() {
        put(planned)
        sandbox.mal.writes = SandboxWrites.Refused
        assertEquals(Outcome.Failure(DataError.Refused), put(planned.copy(watchedEpisodes = 2)))
        sandbox.mal.writes = SandboxWrites.Fail
        assertEquals(Outcome.Failure(DataError.ServerError), put(null as SentValues?))
        assertTrue(runBlocking { client.fetch() } is Outcome.Success)
        sandbox.mal.writes = SandboxWrites.Work
        sandbox.mal.offline = true
        assertEquals(Outcome.Failure(DataError.NoConnection), runBlocking { client.fetch() })
        sandbox.mal.offline = false
        sandbox.mal.delayMillis = 300
        val start = System.nanoTime()
        assertTrue(runBlocking { client.fetch() } is Outcome.Success)
        assertTrue(Duration.ofNanos(System.nanoTime() - start) >= Duration.ofMillis(300))
        assertEquals(planned, read()!!.values)
    }

    @Test
    fun `the list and the tokens outlive the process in their files`() {
        val folder = sandbox.folder.newFolder()
        val tokens = SandboxTokens(sandbox.clock, File(folder, "tokens"))
        val pair = tokens.granted()
        val first = SandboxList(sandboxTitles, sandbox.clock, File(folder, "list"))
        sandbox.clock.now = sandbox.clock.now.plusSeconds(3600)
        assertEquals(first.entries(), SandboxList(sandboxTitles, sandbox.clock, File(folder, "list")).entries())
        assertTrue(first.write(FINISHED, mapOf("status" to "dropped", "score" to "3")) is Written.Done)
        assertTrue(first.remove(first.entries().first { it.first != FINISHED }.first))

        val second = SandboxList(sandboxTitles, sandbox.clock, File(folder, "list"))
        assertEquals(first.entries(), second.entries())
        assertTrue(SandboxTokens(sandbox.clock, File(folder, "tokens")).accepts(pair.first))
        second.reset()
        assertEquals(
            SandboxTitles.startingList(sandboxTitles, sandbox.clock.now),
            SandboxList(sandboxTitles, sandbox.clock, File(folder, "list")).entries().toMap(),
        )
    }

    // Made up and obviously so: no value of anyone's, and no picture of MAL's.
    @Test
    fun `the list the sandbox starts with holds the five statuses, made-up values and only the sandbox's own titles`() {
        val list = SandboxTitles.startingList(sandboxTitles, Instant.parse("2026-11-13T10:00:00Z"))
        assertEquals(ListStatusCodes, list.values.map { it.status }.toSet())
        assertTrue(list.keys.all { it in sandboxTitles })
        assertTrue(list.values.all { (it.startDate ?: "2001").startsWith("2001") && (it.finishDate ?: "2001").startsWith("2001") })
        for (title in sandboxTitles.values) assertFalse(title.toString().contains("cdn.myanimelist.net"))
        assertTrue(sandboxTitles.size > 50)
        assertFalse(sandboxTitles.values.any { "list_status" in it || "my_list_status" in it })
    }

    private companion object {
        const val FINISHED = 1L
        const val LIST = "GET sandbox.invalid/v2/users/@me/animelist"
        const val TOKEN = "POST sandbox.invalid/v1/oauth2/token"
        const val TOTAL = 26
        const val NO_TOTAL = 21L
        val day = PartialDate.Day(LocalDate.of(2001, 1, 1))
    }
}

private operator fun JsonObject.minus(key: String) = JsonObject(filterKeys { it != key })

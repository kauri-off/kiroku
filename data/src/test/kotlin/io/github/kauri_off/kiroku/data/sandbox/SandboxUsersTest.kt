package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.data.list.ListUser
import io.github.kauri_off.kiroku.data.list.MalLists
import io.github.kauri_off.kiroku.data.network.MalFields
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder
import java.time.Duration
import java.time.OffsetDateTime

// The sandbox's other users through the app's client, held to MAL's committed answers for other users' lists.
@RunWith(RobolectricTestRunner::class)
class SandboxUsersTest {
    private val titles = sandboxTitles.toMutableMap()
    private var public: Map<Long, Listed>? = null

    @get:Rule
    internal val sandbox = SandboxFixture(titles, public = { public })

    private val lists by lazy { MalLists(sandbox.client.http) }

    private fun parse(text: String) = Json.parseToJsonElement(text)

    private fun pageOf(
        name: String,
        query: String = "limit=10",
        bearer: String? = null,
    ) = sandbox.ask("GET", "users/$name/animelist?fields=${MalFields.LIST_ENTRY}&nsfw=true&sort=anime_title&$query", bearer = bearer)

    private fun read(name: String): Outcome<List<ListEntry>> = runBlocking { lists.list(ListUser.Named(name)) }

    private val listAsked get() = sandbox.mal.asked.filter { it.endsWith("/animelist") }

    // The public user as the recorded one: their titles and entries; MAL's pages of ten are then the sandbox's.
    @Test
    fun `list_public_1 to list_public_3 - given their entries, the pages hold the same titles in the same order, values and offsets`() {
        val pages = (1..3).map { parse(recorded("list_public_$it.200.json")).jsonObject }
        val items = pages.flatMap { it.getValue("data").jsonArray }.map { it.jsonObject }
        titles.clear()
        // Reversed, so that the order of the pages is the sandbox's own.
        public =
            items.reversed().associate { item ->
                val node = item.getValue("node").jsonObject
                titles[node.id!!] = node
                node.id!! to listed(item.getValue("list_status").jsonObject)
            }
        pages.forEachIndexed { i, page ->
            val (status, body) = pageOf(SandboxUsers.PUBLIC, "limit=10&offset=${i * 10}")
            assertEquals(200, status)
            val given = parse(body).jsonObject
            assertEquals("page ${i + 1}", page.getValue("data"), given.getValue("data"))
            val hisPaging = page.getValue("paging").jsonObject
            val myPaging = given.getValue("paging").jsonObject
            assertEquals(hisPaging.keys, myPaging.keys)
            for (key in hisPaging.keys) assertEquals(key, query(hisPaging, key), query(myPaging, key))
        }
    }

    @Test
    fun `the private user and a name nobody has are answered as MAL answered them, in any letter case`() {
        val private = 403 to parse(recorded("list_private.403.json"))
        for (name in listOf(SandboxUsers.PRIVATE, SandboxUsers.PRIVATE.uppercase(), SandboxUsers.PRIVATE.lowercase())) {
            val (status, body) = pageOf(name)
            assertEquals(name, private, status to parse(body))
        }
        val names =
            mapOf(
                "kiroku-no-user-0" to "list_unknown_user.404.json",
                "kiroku no user" to "list_impossible_name.404.json",
                "${SandboxUsers.PRIVATE}x" to "list_unknown_user.404.json",
                "${SandboxUsers.PUBLIC}%2Fanimelist" to "list_unknown_user.404.json",
                "${SandboxUsers.PRIVATE}%2F" to "list_unknown_user.404.json",
            )
        for ((name, answer) in names) {
            val (status, body) = pageOf(name)
            assertEquals(name, 404 to parse(recorded(answer)), status to parse(body))
        }
        assertEquals(Outcome.Failure(DataError.PrivateList), read(SandboxUsers.PRIVATE.uppercase()))
        assertEquals(Outcome.Failure(DataError.UnknownUser), read("kiroku no user"))
        assertEquals(Outcome.Failure(DataError.UnknownUser), read("${SandboxUsers.PUBLIC}/animelist"))
    }

    @Test
    fun `MalLists reads the public list of several thousand whole, each entry once, in pages of a thousand`() {
        val expected = sandbox.list.usersList(SandboxUsers.PUBLIC) as UsersList.Public
        val entries = (read(SandboxUsers.PUBLIC) as Outcome.Success).value
        assertTrue(entries.size > 3000)
        assertEquals(SandboxUsers.publicList(sandboxTitles).size, entries.size)
        assertEquals(expected.entries.map { it.first }, entries.map { it.anime.id.value })
        assertEquals(expected.entries.map { it.second.watched }, entries.map { it.values.watchedEpisodes })
        assertEquals((entries.size + 999) / 1000, listAsked.size)
        assertEquals(ListStatusCodes, expected.entries.map { it.second.status }.toSet())
    }

    @Test
    fun `the public list is made of the sandbox's titles and their copies, each a title the sandbox answers and can list`() {
        val entries = (sandbox.list.usersList(SandboxUsers.PUBLIC) as UsersList.Public).entries
        val pictures = sandboxTitles.values.map { it["main_picture"] }.toSet()
        val names = mutableSetOf<String?>()
        for ((id, listed) in entries) {
            val title = checkNotNull(sandbox.list.title(id)) { "$id" }
            assertTrue("$id: a title of another entry", names.add(title.text("title")))
            assertTrue("$id", title["main_picture"] in pictures)
            title.episodes?.let { assertTrue("$id", listed.watched <= it) }
        }
        assertTrue(sandboxTitles.keys.all { id -> entries.any { it.first == id } })
        val copy = entries.first { it.first !in sandboxTitles }.first
        val (status, body) = sandbox.ask("GET", "anime/$copy?fields=${MalFields.DETAILS}")
        assertEquals(200, status)
        assertEquals(copy, parse(body).jsonObject.id)
        assertTrue(sandbox.list.write(copy, mapOf("status" to "plan_to_watch")) is Written.Done)
        assertEquals(404, sandbox.ask("GET", "anime/${copy + 1_000_000L * SandboxUsers.COPIES}?fields=id").first)
    }

    @Test
    fun `a page holds the number asked, from the offset asked, up to a thousand`() {
        val first = parse(pageOf(SandboxUsers.PUBLIC, "limit=7&offset=0").second).jsonObject
        assertEquals(7, first.getValue("data").jsonArray.size)
        assertEquals("7", query(first.getValue("paging").jsonObject, "next")!!["offset"])
        val next = parse(pageOf(SandboxUsers.PUBLIC, "limit=7&offset=7").second).jsonObject
        assertNotEquals(first.getValue("data").jsonArray.first(), next.getValue("data").jsonArray.first())
        assertEquals(
            1000,
            parse(pageOf(SandboxUsers.PUBLIC, "limit=5000").second)
                .jsonObject
                .getValue("data")
                .jsonArray.size,
        )
    }

    @Test
    fun `another user's list is answered with a token as without one, and a name in another letter case is the same user`() {
        for (name in listOf(SandboxUsers.PUBLIC, SandboxUsers.PRIVATE, "kiroku-no-user-0")) {
            assertEquals(name, pageOf(name), pageOf(name, bearer = sandbox.access))
        }

        // Its links name the user as asked: only the entries are compared.
        fun entries(name: String) = pageOf(name).let { (status, body) -> status to parse(body).jsonObject["data"] }
        val public = entries(SandboxUsers.PUBLIC)
        assertEquals(200, public.first)
        assertEquals(public, entries(SandboxUsers.PUBLIC.uppercase()))
        assertEquals(public, entries(SandboxUsers.PUBLIC.lowercase()))
        assertEquals(200, pageOf(SandboxMal.USER_NAME.uppercase()).first)
        assertEquals(401, pageOf("@me").first)
    }

    @Test
    fun `offline the sandbox answers no other user's list, and a slow sandbox answers each page late`() {
        sandbox.mal.offline = true
        assertEquals(Outcome.Failure(DataError.NoConnection), read(SandboxUsers.PUBLIC))
        assertEquals(Outcome.Failure(DataError.NoConnection), read(SandboxUsers.PRIVATE))
        sandbox.mal.offline = false
        sandbox.mal.delayMillis = 100
        val before = listAsked.size
        val start = System.nanoTime()
        assertTrue(read(SandboxUsers.PUBLIC) is Outcome.Success)
        val pages = listAsked.size - before
        assertTrue(pages > 1)
        assertTrue(Duration.ofNanos(System.nanoTime() - start) >= Duration.ofMillis(100L * pages))
    }

    private fun listed(status: JsonObject) =
        Listed(
            status = status.text("status")!!,
            score = status.getValue("score").jsonPrimitive.int,
            watched = status.getValue("num_episodes_watched").jsonPrimitive.int,
            startDate = status.text("start_date"),
            finishDate = status.text("finish_date"),
            updatedAt = OffsetDateTime.parse(status.text("updated_at")!!).toInstant(),
        )

    // A paging link's query, which is all of it the sandbox and MAL share.
    private fun query(
        paging: JsonObject,
        key: String,
    ): Map<String, String>? =
        paging[key]?.jsonPrimitive?.content?.let { link ->
            link
                .substringAfter('?')
                .split('&')
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        }
}

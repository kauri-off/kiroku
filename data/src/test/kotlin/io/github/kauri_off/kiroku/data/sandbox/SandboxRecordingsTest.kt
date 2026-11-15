package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.recorder.OwnTitleWrites
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.OffsetDateTime

// Given each recorded answer's state, the sandbox gives it back; failures name the answer and key, never a value.
@RunWith(RobolectricTestRunner::class)
class SandboxRecordingsTest {
    private val titles = mutableMapOf<Long, JsonObject>()
    private var state = emptyMap<Long, Listed>()

    @get:Rule
    internal val sandbox = SandboxFixture(titles, starting = { state })

    private class Answer(
        val status: Int,
        val json: JsonElement,
    )

    private fun answer(name: String): Answer {
        val recorded = OwnerRecordings.named(name)
        return OwnerRecordings.read(name) { Answer(recorded.status, Json.parseToJsonElement(recorded.body)) }
    }

    // The recorder's title, which MAL knew and which was not on the list.
    private val title: JsonObject by lazy { answer(ABSENT).json.jsonObject.also { titles[checkNotNull(it.id)] = it } }
    private val id get() = checkNotNull(title.id)
    private val total get() = checkNotNull(title.episodes) { "$ABSENT has no episode count" }

    private fun ask(
        name: String,
        method: String,
        path: String,
        form: Map<String, String>? = null,
    ): Answer {
        val (status, body) = sandbox.ask(method, path, form)
        return OwnerRecordings.read("the sandbox's $name") { Answer(status, Json.parseToJsonElement(body)) }
    }

    private fun readBack(name: String) = ask(name, "GET", "anime/$id?fields=$READ_BACK")

    // The entry an answer about the title gives, as the sandbox keeps it.
    private fun listedIn(
        name: String,
        answer: Answer,
    ): Listed? {
        val values = (answer.json as? JsonObject)?.get(OWN) as? JsonObject ?: return null
        return OwnerRecordings.read(name) {
            Listed(
                status = values.text("status")!!,
                score = (values["score"] as JsonPrimitive).intOrNull!!,
                watched = (values["num_episodes_watched"] as JsonPrimitive).intOrNull!!,
                startDate = values.text("start_date"),
                finishDate = values.text("finish_date"),
                updatedAt = timeOf(values.text("updated_at")!!),
            )
        }
    }

    // Status, shape, and the values the state decides; [values] names where the entry's values are.
    private fun assertGivesBack(
        name: String,
        recorded: Answer,
        given: Answer,
        values: (JsonElement) -> JsonObject? = { (it as? JsonObject)?.get(OWN) as? JsonObject },
    ) {
        assertTrue("$name: status", recorded.status == given.status)
        assertTrue("$name: keys or types", shape(recorded.json) == shape(given.json))
        val mine = values(given.json)
        val his = values(recorded.json)
        assertTrue("$name: values present", (mine == null) == (his == null))
        if (mine == null || his == null) return
        for (key in VALUES) assertTrue("$name: $key", mine[key] == his[key])
        assertTrue("$name: updated_at", mine.text("updated_at")?.let(::timeOf) == his.text("updated_at")?.let(::timeOf))
    }

    // The sandbox holds [entry] for the title, and its clock is at [now].
    private fun given(
        entry: Listed?,
        now: Instant,
    ) {
        state = listOfNotNull(entry?.let { id to it }).toMap()
        sandbox.list.reset()
        sandbox.clock.now = now
    }

    @Test
    fun `entry_absent - a title not on the list is answered without list values`() {
        given(null, Instant.EPOCH)
        assertGivesBack(ABSENT, answer(ABSENT), ask(ABSENT, "GET", "anime/$id?fields=${MalFields.OWN_ENTRY}"))
    }

    // Each write from the entry its read-back before it gave, at the moment MAL said it changed it, then its own read-back.
    @Test
    fun `each recorded write and its read-back - given the entry before it, the sandbox answers as MAL did`() {
        title
        var before: Listed? = null
        for (write in OwnTitleWrites) {
            val name = write.name
            val recorded = answer(name)
            val written = (recorded.json as? JsonObject)?.text("updated_at")?.let(::timeOf)
            given(before, written ?: Instant.EPOCH)
            val given = ask(name, "PATCH", "anime/$id/my_list_status", write.form(total))
            assertGivesBack(name, recorded, given) { it as? JsonObject }
            if (recorded.status != 200) assertTrue("$name: an answer of 200", given.status != 200)

            val read = answer("${name}_read")
            assertGivesBack("${name}_read", read, readBack("${name}_read"))
            before = listedIn("${name}_read", read)
            if (recorded.status != 200) assertTrue("${name}_read: the entry changed", before == state[id])
        }
    }

    @Test
    fun `entry_full - the title's own answer gives the entry the last read-back gave`() {
        given(listedIn(LAST_READ, answer(LAST_READ).also { title }), Instant.EPOCH)
        assertGivesBack("entry_full", answer("entry_full"), ask("entry_full", "GET", "anime/$id?fields=${MalFields.OWN_ENTRY}"))
    }

    @Test
    fun `removed, removed_read and removed_again - a removal, of a title on the list and of one no longer on it`() {
        given(listedIn(LAST_READ, answer(LAST_READ).also { title }), Instant.EPOCH)
        assertGivesBack("removed", answer("removed"), ask("removed", "DELETE", "anime/$id/my_list_status")) { null }
        assertTrue("removed: body", answer("removed").json == ask("removed", "DELETE", "anime/$id/my_list_status").json)
        assertGivesBack("removed_read", answer("removed_read"), readBack("removed_read"))
        assertGivesBack("removed_again", answer("removed_again"), ask("removed_again", "DELETE", "anime/$id/my_list_status")) { null }
    }

    // The user's own list as three pages: given every title and entry on them, the sandbox makes the same three.
    @Test
    fun `own_list_1 to own_list_3 - given the list, the pages hold the same titles in the same order, values and offsets`() {
        val pages = (1..3).map { "own_list_$it" to answer("own_list_$it") }
        val listed = mutableMapOf<Long, Listed>()
        for ((name, page) in pages) {
            OwnerRecordings.read(name) {
                for (item in page.json.jsonObject["data"]!!.jsonArray) {
                    val node = item.jsonObject["node"]!!.jsonObject
                    titles[node.id!!] = node
                    listed[node.id!!] = listedIn(name, Answer(200, JsonObject(mapOf(OWN to item.jsonObject["list_status"]!!))))!!
                }
            }
        }
        given(null, Instant.EPOCH)
        state = listed
        sandbox.list.reset()
        val size =
            pages
                .first()
                .second.json.jsonObject["data"]!!
                .jsonArray.size
        pages.forEachIndexed { i, (name, page) ->
            val given =
                ask(
                    name,
                    "GET",
                    "users/@me/animelist?fields=${MalFields.LIST_ENTRY}&nsfw=true&sort=anime_title&limit=$size&offset=${i * size}",
                )
            assertTrue("$name: status", page.status == given.status)
            val his = page.json.jsonObject["data"]!!.jsonArray
            val mine = given.json.jsonObject["data"]!!.jsonArray
            assertTrue("$name: entries", his.size == mine.size)
            his.indices.forEach { at ->
                val hisItem = his[at].jsonObject
                val myItem = mine[at].jsonObject
                assertTrue("$name, entry $at: title", hisItem["node"]!!.jsonObject.id == myItem["node"]!!.jsonObject.id)
                assertGivesBack(
                    "$name, entry $at",
                    Answer(200, hisItem),
                    Answer(200, myItem),
                ) { (it as JsonObject)["list_status"] as JsonObject }
            }
            val hisPaging = page.json.jsonObject["paging"]!!.jsonObject
            val myPaging = given.json.jsonObject["paging"]!!.jsonObject
            assertTrue("$name: paging keys", hisPaging.keys == myPaging.keys)
            for (key in hisPaging.keys) assertTrue("$name: paging $key offset", offsetIn(hisPaging[key]) == offsetIn(myPaging[key]))
        }
    }

    private fun offsetIn(link: JsonElement?): String? =
        (link as? JsonPrimitive)?.contentOrNull?.substringAfter("offset=", "")?.substringBefore('&')

    private companion object {
        const val ABSENT = "entry_absent"
        const val OWN = "my_list_status"
        const val LAST_READ = "status_refused_read"
        val READ_BACK = "id,num_episodes,my_list_status{${MalFields.LIST_VALUES}}"
        val VALUES = listOf("status", "score", "num_episodes_watched", "start_date", "finish_date")

        fun timeOf(text: String): Instant = OffsetDateTime.parse(text).toInstant()
    }
}

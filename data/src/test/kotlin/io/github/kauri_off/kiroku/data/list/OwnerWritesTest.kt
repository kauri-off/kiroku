package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.data.anime.AnimeNode
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.toAnime
import io.github.kauri_off.kiroku.data.db.listStatuses
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.recorder.OwnTitleWrites
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.runBlocking
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLDecoder
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.CopyOnWriteArrayList

// The client on the signed-in recorder's writes and read-backs as MAL sent them, one test per request; skipped where they
// are absent. Each answer is served only to the request the recorder sent for it. A failure names the answer and the
// field or key, never what it holds.
class OwnerWritesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val sessions by lazy { SessionFixture(folder.newFolder()) }

    private val client by lazy {
        val session = sessions.open()
        runBlocking { session.signIn(TokenGrant("access-1", "refresh-1")) }
        MalRemoteList(network.http, session)
    }

    @After
    fun close() = runBlocking { sessions.close() }

    // The recorder's title, which is not on the list before the first write: its public data.
    private val title: Anime by lazy {
        val answer = OwnerRecordings.named(ABSENT)
        OwnerRecordings.read(ABSENT) {
            MalJson.decodeFromJsonElement(AnimeNode.serializer(), MalJson.parseToJsonElement(answer.body)).toAnime(Repairs())
        }
    }

    private val total: Int get() = checkNotNull(title.malEpisodes) { "$ABSENT has no episode count" }

    private val entryPath get() = "/v2/anime/${title.id.value}"

    // What the recorder sent for the write [name], its form taken from the recorder's own list.
    private fun recorded(name: String): Map<String, String> = OwnTitleWrites.single { it.name == name }.form(total)

    // A request the recorded answer is served to; [form] is compared key by key, or by its keys alone for [keysOnly].
    private class Asked(
        val method: String,
        val path: String,
        val fields: String? = null,
        val form: Map<String, String>? = null,
        val keysOnly: Boolean = false,
    )

    private val differences = CopyOnWriteArrayList<String>()
    private val sentForms = CopyOnWriteArrayList<Map<String, String>>()

    // The answer [name] for the request [asked] alone; another request is answered 500, and what differs is noted by name.
    private fun served(
        name: String,
        asked: Asked,
    ) {
        val answer = OwnerRecordings.named(name)
        sentForms.clear()
        network.answers { request ->
            val form = request.form()
            if (request.method != "GET") sentForms += form
            val differs = differences(asked, request, form)
            if (differs.isEmpty()) {
                answer(answer.status, answer.body)
            } else {
                differences += differs.map { "$name: $it" }
                answer(500, "")
            }
        }
    }

    private fun differences(
        asked: Asked,
        request: RecordedRequest,
        form: Map<String, String>,
    ): List<String> {
        val differs = mutableListOf<String>()
        if (request.method != asked.method) differs += "method"
        if (request.url.encodedPath != asked.path) differs += "path"
        if (asked.fields != null && request.url.queryParameter("fields") != asked.fields) differs += "fields"
        for ((key, value) in asked.form.orEmpty()) {
            when {
                key !in form -> differs += "form key $key"
                !asked.keysOnly && form[key] != value.sentAs() -> differs += "value of $key"
            }
        }
        return differs
    }

    // The recorder's zero date is no date, as dates_zeroed's read-back shows; the client sends no date as an empty one.
    private fun String.sentAs(): String = if (this == ZERO_DATE) "" else this

    private fun RecordedRequest.form(): Map<String, String> =
        body
            ?.utf8()
            .orEmpty()
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    private fun assertAskedAsRecorded() = assertTrue(differences.joinToString(), differences.isEmpty())

    private fun put(
        name: String,
        values: ListValues?,
        asked: Asked,
    ): Outcome<ListEntry?> {
        served(name, asked)
        return runBlocking { client.put(title, values) }.also { assertAskedAsRecorded() }
    }

    // A read-back: the recorder asked fewer fields than the client does, so only the method and the path are compared.
    private fun readBack(
        name: String,
        fields: String? = null,
    ): ListEntry? {
        served(name, Asked("GET", entryPath, fields))
        val outcome = runBlocking { client.fetchEntry(title.id) }
        assertAskedAsRecorded()
        assertTrue("$name could not be read", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    private fun before(name: String): ListEntry = checkNotNull(readBack(name)) { "$name holds no entry" }

    // The write [name] as the client sends it for the same change: it carries the recorder's [keys] with the recorder's
    // values, and the entry's other values beside them. Its answer is the entry its read-back gives, values and time
    // alike, changed after [earlier].
    private fun written(
        name: String,
        earlier: ListEntry?,
        sent: ListValues,
        vararg keys: String,
    ): ListValues {
        val form = recorded(name)
        assertTrue("$name: the recorder sent other keys", form.keys == keys.toSet())
        val outcome = put(name, sent, Asked("PATCH", "$entryPath/my_list_status", form = form))
        assertTrue("$name: the client sends ${FIVE - form.keys} beside the recorder's keys", sentForms.single().keys == FIVE)
        assertTrue("$name could not be read", outcome is Outcome.Success)
        val entry = (outcome as Outcome.Success).value
        assertTrue("$name holds no entry", entry != null)
        assertTrue("$name answered for another title", entry!!.anime == title)
        val read = readBack("${name}_read")
        assertTrue("${name}_read holds no entry", read != null)
        assertTrue("$name differs from ${name}_read in its values", entry.values == read!!.values)
        assertTrue("$name differs from ${name}_read in updated_at", entry.updatedAt == read.updatedAt)
        if (earlier != null) assertTrue("$name is not later than the read-back before it", entry.updatedAt > earlier.updatedAt)
        return entry.values
    }

    private fun ListValues.keeps(
        name: String,
        earlier: ListValues,
        vararg changed: String,
    ) {
        val fields =
            mapOf(
                "status" to (status == earlier.status),
                "num_episodes_watched" to (watchedEpisodes == earlier.watchedEpisodes),
                "score" to (score == earlier.score),
                "start_date" to (startDate == earlier.startDate),
                "finish_date" to (finishDate == earlier.finishDate),
            )
        for ((field, same) in fields - changed.toSet()) assertTrue("$name changed $field", same)
    }

    private fun day(
        year: Int,
        month: Int,
        day: Int,
    ) = PartialDate.Day(LocalDate.of(year, month, day))

    @Test
    fun `entry_absent - a title not on the list is read as having no entry`() {
        assertTrue("$ABSENT holds an entry", readBack(ABSENT, MalFields.OWN_ENTRY) == null)
        assertTrue("$ABSENT repaired a value", network.unexpected.isEmpty())
    }

    @Test
    fun `add_planned - a status alone adds the title with no count, score or date, where the client sends all five`() {
        val values = written("add_planned", null, PLANNED, "status")
        assertTrue("add_planned: status", values.status == ListStatus.Planned)
        assertTrue("add_planned: num_episodes_watched", values.watchedEpisodes == 0)
        assertTrue("add_planned: score", values.score == null)
        assertTrue("add_planned: start_date", values.startDate == null)
        assertTrue("add_planned: finish_date", values.finishDate == null)
    }

    // MAL changes no status and no date on its own: a write without a status keeps it.
    @Test
    fun `count_first - the first episode of a planned title changes the count alone, where the client sends the rest as it was`() {
        val earlier = before("add_planned_read")
        val values = written("count_first", earlier, earlier.values.copy(watchedEpisodes = 1), "num_watched_episodes")
        assertTrue("count_first: num_episodes_watched", values.watchedEpisodes == 1)
        values.keeps("count_first", earlier.values, "num_episodes_watched")
    }

    @Test
    fun `count_total - a count that reaches the total changes the count alone, where the client sends the rest as it was`() {
        val earlier = before("count_first_read")
        val values = written("count_total", earlier, earlier.values.copy(watchedEpisodes = total), "num_watched_episodes")
        assertTrue("count_total: num_episodes_watched", values.watchedEpisodes == total)
        values.keeps("count_total", earlier.values, "num_episodes_watched")
    }

    // W9 differs: the count is not above a known total afterwards. Sent from the total, so capping and ignoring look alike.
    @Test
    fun `count_above_total - a count above the total is answered as done and is not above it, where the client sends the rest as it was`() {
        val earlier = before("count_total_read")
        val values = written("count_above_total", earlier, earlier.values.copy(watchedEpisodes = total + 1), "num_watched_episodes")
        assertTrue("count_above_total: num_episodes_watched", values.watchedEpisodes == total)
        values.keeps("count_above_total", earlier.values)
    }

    @Test
    fun `count_below_total - a count below the total is taken as sent and nothing else changes, the client sending the rest as it was`() {
        val earlier = before("count_above_total_read")
        val values = written("count_below_total", earlier, earlier.values.copy(watchedEpisodes = total - 1), "num_watched_episodes")
        assertTrue("count_below_total: num_episodes_watched", values.watchedEpisodes == total - 1)
        values.keeps("count_below_total", earlier.values, "num_episodes_watched")
    }

    @Test
    fun `completed - Completed alone brings neither the count to the total nor a finish date, where the client sends the rest as it was`() {
        val earlier = before("count_below_total_read")
        val values = written("completed", earlier, earlier.values.copy(status = ListStatus.Completed), "status")
        assertTrue("completed: status", values.status == ListStatus.Completed)
        values.keeps("completed", earlier.values, "status")
    }

    @Test
    fun `finish_set - a finish date is set as sent, where the client sends the rest as it was`() {
        val earlier = before("completed_read")
        val values = written("finish_set", earlier, earlier.values.copy(finishDate = day(2020, 2, 2)), "finish_date")
        assertTrue("finish_set: finish_date", values.finishDate == day(2020, 2, 2))
        values.keeps("finish_set", earlier.values, "finish_date")
    }

    // W3 holds: an empty date clears MAL's.
    @Test
    fun `finish_cleared - an empty finish date clears it, where the client sends the rest as it was`() {
        val earlier = before("finish_set_read")
        val values = written("finish_cleared", earlier, earlier.values.copy(finishDate = null), "finish_date")
        assertTrue("finish_cleared: finish_date", values.finishDate == null)
        values.keeps("finish_cleared", earlier.values, "finish_date")
    }

    @Test
    fun `start_set - a start date is set as sent, where the client sends the rest as it was`() {
        val earlier = before("finish_cleared_read")
        val values = written("start_set", earlier, earlier.values.copy(startDate = day(2020, 1, 1)), "start_date")
        assertTrue("start_set: start_date", values.startDate == day(2020, 1, 1))
        values.keeps("start_set", earlier.values, "start_date")
    }

    @Test
    fun `start_cleared - an empty start date clears it, where the client sends the rest as it was`() {
        val earlier = before("start_set_read")
        val values = written("start_cleared", earlier, earlier.values.copy(startDate = null), "start_date")
        assertTrue("start_cleared: start_date", values.startDate == null)
        values.keeps("start_cleared", earlier.values, "start_date")
    }

    @Test
    fun `dates_reversed - a finish before the start is kept as sent, where the client sends the rest as it was`() {
        val earlier = before("start_cleared_read")
        val sent = earlier.values.copy(startDate = day(2020, 3, 3), finishDate = day(2020, 2, 2))
        val values = written("dates_reversed", earlier, sent, "start_date", "finish_date")
        assertTrue("dates_reversed: start_date", values.startDate == day(2020, 3, 3))
        assertTrue("dates_reversed: finish_date", values.finishDate == day(2020, 2, 2))
        values.keeps("dates_reversed", earlier.values, "start_date", "finish_date")
    }

    // The recorder sent MAL's zero date; the client sends an empty one for the same change, which clears a date too.
    @Test
    fun `dates_zeroed - MAL's zero date clears both dates, where the client sends empty dates and the rest as it was`() {
        val earlier = before("dates_reversed_read")
        assertTrue("dates_zeroed: the recorder sent no zero date", recorded("dates_zeroed").values.all { it == ZERO_DATE })
        val values = written("dates_zeroed", earlier, earlier.values.copy(startDate = null, finishDate = null), "start_date", "finish_date")
        assertTrue("dates_zeroed: start_date", values.startDate == null)
        assertTrue("dates_zeroed: finish_date", values.finishDate == null)
        values.keeps("dates_zeroed", earlier.values, "start_date", "finish_date")
    }

    @Test
    fun `date_partial - a year and month is kept as a year and month, where the client sends the rest as it was`() {
        val earlier = before("dates_zeroed_read")
        val month = PartialDate.Month(YearMonth.of(2020, 5))
        val values = written("date_partial", earlier, earlier.values.copy(startDate = month), "start_date")
        assertTrue("date_partial: start_date", values.startDate == month)
        values.keeps("date_partial", earlier.values, "start_date")
    }

    @Test
    fun `date_future - a finish date in the future is kept as sent, where the client sends the rest as it was`() {
        val earlier = before("date_partial_read")
        val values = written("date_future", earlier, earlier.values.copy(finishDate = day(2100, 1, 1)), "finish_date")
        assertTrue("date_future: finish_date", values.finishDate == day(2100, 1, 1))
        values.keeps("date_future", earlier.values, "finish_date")
    }

    @Test
    fun `score_set - a score is set as sent, where the client sends the rest as it was`() {
        val earlier = before("date_future_read")
        val values = written("score_set", earlier, earlier.values.copy(score = 7), "score")
        assertTrue("score_set: score", values.score == 7)
        values.keeps("score_set", earlier.values, "score")
    }

    @Test
    fun `score_cleared - a score of 0 is no score, where the client sends the rest as it was`() {
        val earlier = before("score_set_read")
        val values = written("score_cleared", earlier, earlier.values.copy(score = null), "score")
        assertTrue("score_cleared: score", values.score == null)
        values.keeps("score_cleared", earlier.values, "score")
    }

    // The recorder sent a value the client cannot hold, so its answer is served to the client's write of the same key: a
    // refused write is a failed write (W2), and changed nothing, not even the time of the last change.
    private fun refused(
        name: String,
        earlier: String,
        key: String,
    ) {
        val form = recorded(name)
        assertTrue("$name: the recorder sent other keys", form.keys == setOf(key))
        assertTrue("$name is not an answer of 400", OwnerRecordings.named(name).status == 400)
        val before = before(earlier)
        val outcome = put(name, before.values, Asked("PATCH", "$entryPath/my_list_status", form = form, keysOnly = true))
        assertTrue("$name: not a refusal of the request", outcome == Outcome.Failure(DataError.InvalidRequest))
        val after = readBack("${name}_read")
        assertTrue("${name}_read holds no entry", after != null)
        assertTrue("${name}_read differs from $earlier in its values", after!!.values == before.values)
        assertTrue("${name}_read differs from $earlier in updated_at", after.updatedAt == before.updatedAt)
    }

    @Test
    fun `score_refused - a score above 10, which the client cannot hold, is refused with 400 and the entry is as it was`() {
        val score = recorded("score_refused").getValue("score").toInt()
        assertTrue("score_refused: a score the client can hold", runCatching { PLANNED.copy(score = score) }.isFailure)
        refused("score_refused", "score_cleared_read", "score")
    }

    @Test
    fun `status_refused - a status MAL does not know, which the client cannot send, is refused with 400 and the entry is as it was`() {
        assertTrue("status_refused: a status the client can send", recorded("status_refused").getValue("status") !in listStatuses.values)
        refused("status_refused", "score_refused_read", "status")
    }

    @Test
    fun `entry_full - the title's own answer gives the entry the last read-back gave, its title read whole`() {
        val last = before("status_refused_read")
        network.unexpected.clear()
        val entry = readBack("entry_full", MalFields.OWN_ENTRY)
        assertTrue("entry_full holds no entry", entry != null)
        assertTrue("entry_full: title repaired", network.unexpected.isEmpty())
        assertTrue("entry_full: another title", entry!!.anime.id == title.id)
        assertTrue("entry_full differs from status_refused_read in its values", entry.values == last.values)
        assertTrue("entry_full differs from status_refused_read in updated_at", entry.updatedAt == last.updatedAt)
    }

    private val removal get() = Asked("DELETE", "$entryPath/my_list_status")

    @Test
    fun `removed - a removal is answered with no entry, and the title is off the list after it`() {
        assertTrue("removed: not a removal", put("removed", null, removal) == Outcome.Success(null))
        assertTrue("removed: a body sent", sentForms.single().isEmpty())
        assertTrue("removed_read holds an entry", readBack("removed_read") == null)
    }

    @Test
    fun `removed_again - removing a title that is not on the list is answered as a removal`() {
        assertTrue("removed_again: not a removal", put("removed_again", null, removal) == Outcome.Success(null))
        val again = OwnerRecordings.named("removed_again")
        val first = OwnerRecordings.named("removed")
        assertTrue("removed_again is answered unlike removed", again.status == first.status && again.body == first.body)
    }

    @Test
    fun `own_list - every entry of the user's own pages written back unchanged carries each value as MAL gave it`() {
        for (name in (1..3).map { "own_list_$it" }) assertWrittenAsGiven(name, OwnerRecordings.named(name).body)
    }

    private companion object {
        const val ABSENT = "entry_absent"
        const val ZERO_DATE = "0000-00-00"
        val PLANNED = ListValues(ListStatus.Planned, 0, null, null, null)
        val FIVE = setOf("status", "score", "num_watched_episodes", "start_date", "finish_date")
    }
}

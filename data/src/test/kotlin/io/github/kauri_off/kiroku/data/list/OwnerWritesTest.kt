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
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.YearMonth

// The client on the signed-in recorder's writes and read-backs as MAL sent them, one test per request; skipped where they
// are absent. A failure names the answer and the value, never what it holds.
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

    private fun served(name: String) {
        val answer = OwnerRecordings.named(name)
        network.always(answer(answer.status, answer.body))
    }

    private fun put(
        name: String,
        values: ListValues?,
    ): Outcome<ListEntry?> {
        served(name)
        return runBlocking { client.put(title, values) }
    }

    private fun readBack(name: String): ListEntry? {
        served(name)
        val outcome = runBlocking { client.fetchEntry(title.id) }
        assertTrue("$name could not be read", outcome is Outcome.Success)
        return (outcome as Outcome.Success).value
    }

    // The write [name] as the client reads it, which is the entry its read-back gives, values and time alike.
    private fun written(
        name: String,
        sent: ListValues = PLANNED,
    ): ListValues {
        val outcome = put(name, sent)
        assertTrue("$name could not be read", outcome is Outcome.Success)
        val entry = (outcome as Outcome.Success).value
        assertTrue("$name holds no entry", entry != null)
        assertTrue("$name answered for another title", entry!!.anime == title)
        val read = readBack("${name}_read")
        assertTrue("${name}_read holds no entry", read != null)
        assertTrue("$name differs from ${name}_read in its values", entry.values == read!!.values)
        assertTrue("$name differs from ${name}_read in updated_at", entry.updatedAt == read.updatedAt)
        return entry.values
    }

    // The values of the read-back before [name], which the write did not carry and so did not change.
    private fun before(name: String): ListEntry = checkNotNull(readBack(name)) { "$name holds no entry" }

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
        assertTrue("$ABSENT holds an entry", readBack(ABSENT) == null)
        assertTrue("$ABSENT repaired a value", network.unexpected.isEmpty())
    }

    @Test
    fun `add_planned - a status alone adds the title, with no count, score or date`() {
        val values = written("add_planned")
        assertTrue("add_planned: status", values.status == ListStatus.Planned)
        assertTrue("add_planned: num_episodes_watched", values.watchedEpisodes == 0)
        assertTrue("add_planned: score", values.score == null)
        assertTrue("add_planned: start_date", values.startDate == null)
        assertTrue("add_planned: finish_date", values.finishDate == null)
    }

    // MAL changes no status and no date on its own: a write without a status keeps it.
    @Test
    fun `count_first - the first episode of a planned title changes the count alone`() {
        val earlier = before("add_planned_read").values
        val values = written("count_first", earlier)
        assertTrue("count_first: num_episodes_watched", values.watchedEpisodes == 1)
        values.keeps("count_first", earlier, "num_episodes_watched")
    }

    @Test
    fun `count_total - a count that reaches the total changes the count alone`() {
        val earlier = before("count_first_read").values
        val values = written("count_total", earlier)
        assertTrue("count_total: num_episodes_watched", values.watchedEpisodes == total)
        values.keeps("count_total", earlier, "num_episodes_watched")
    }

    // W9 differs: MAL leaves a count above a known total at the total.
    @Test
    fun `count_above_total - a count above the total is answered as done, and the count stays at the total`() {
        val earlier = before("count_total_read").values
        val values = written("count_above_total", earlier.copy(watchedEpisodes = total + 1))
        assertTrue("count_above_total: num_episodes_watched", values.watchedEpisodes == total)
        values.keeps("count_above_total", earlier)
    }

    @Test
    fun `count_below_total - a count below the total is taken as sent, and nothing else changes`() {
        val earlier = before("count_above_total_read").values
        val values = written("count_below_total", earlier.copy(watchedEpisodes = total - 1))
        assertTrue("count_below_total: num_episodes_watched", values.watchedEpisodes == total - 1)
        values.keeps("count_below_total", earlier, "num_episodes_watched")
    }

    @Test
    fun `completed - the status Completed alone brings neither the count to the total nor a finish date`() {
        val earlier = before("count_below_total_read").values
        val values = written("completed", earlier.copy(status = ListStatus.Completed))
        assertTrue("completed: status", values.status == ListStatus.Completed)
        values.keeps("completed", earlier, "status")
    }

    @Test
    fun `finish_set - a finish date is set as sent`() {
        val earlier = before("completed_read").values
        val values = written("finish_set", earlier.copy(finishDate = day(2020, 2, 2)))
        assertTrue("finish_set: finish_date", values.finishDate == day(2020, 2, 2))
        values.keeps("finish_set", earlier, "finish_date")
    }

    // W3 holds: an empty date clears MAL's.
    @Test
    fun `finish_cleared - an empty finish date clears it`() {
        val earlier = before("finish_set_read").values
        val values = written("finish_cleared", earlier.copy(finishDate = null))
        assertTrue("finish_cleared: finish_date", values.finishDate == null)
        values.keeps("finish_cleared", earlier, "finish_date")
    }

    @Test
    fun `start_set - a start date is set as sent`() {
        val earlier = before("finish_cleared_read").values
        val values = written("start_set", earlier.copy(startDate = day(2020, 1, 1)))
        assertTrue("start_set: start_date", values.startDate == day(2020, 1, 1))
        values.keeps("start_set", earlier, "start_date")
    }

    @Test
    fun `start_cleared - an empty start date clears it`() {
        val earlier = before("start_set_read").values
        val values = written("start_cleared", earlier.copy(startDate = null))
        assertTrue("start_cleared: start_date", values.startDate == null)
        values.keeps("start_cleared", earlier, "start_date")
    }

    @Test
    fun `dates_reversed - a finish before the start is kept as sent`() {
        val earlier = before("start_cleared_read").values
        val values = written("dates_reversed", earlier.copy(startDate = day(2020, 3, 3), finishDate = day(2020, 2, 2)))
        assertTrue("dates_reversed: start_date", values.startDate == day(2020, 3, 3))
        assertTrue("dates_reversed: finish_date", values.finishDate == day(2020, 2, 2))
        values.keeps("dates_reversed", earlier, "start_date", "finish_date")
    }

    @Test
    fun `dates_zeroed - MAL's zero date clears both dates as an empty one does`() {
        val earlier = before("dates_reversed_read").values
        val values = written("dates_zeroed", earlier.copy(startDate = null, finishDate = null))
        assertTrue("dates_zeroed: start_date", values.startDate == null)
        assertTrue("dates_zeroed: finish_date", values.finishDate == null)
        values.keeps("dates_zeroed", earlier, "start_date", "finish_date")
    }

    @Test
    fun `date_partial - a year and month is kept as a year and month`() {
        val earlier = before("dates_zeroed_read").values
        val month = PartialDate.Month(YearMonth.of(2020, 5))
        val values = written("date_partial", earlier.copy(startDate = month))
        assertTrue("date_partial: start_date", values.startDate == month)
        values.keeps("date_partial", earlier, "start_date")
    }

    @Test
    fun `date_future - a finish date in the future is kept as sent`() {
        val earlier = before("date_partial_read").values
        val values = written("date_future", earlier.copy(finishDate = day(2100, 1, 1)))
        assertTrue("date_future: finish_date", values.finishDate == day(2100, 1, 1))
        values.keeps("date_future", earlier, "finish_date")
    }

    @Test
    fun `score_set - a score is set as sent`() {
        val earlier = before("date_future_read").values
        val values = written("score_set", earlier.copy(score = 7))
        assertTrue("score_set: score", values.score == 7)
        values.keeps("score_set", earlier, "score")
    }

    @Test
    fun `score_cleared - a score of 0 is no score`() {
        val earlier = before("score_set_read").values
        val values = written("score_cleared", earlier.copy(score = null))
        assertTrue("score_cleared: score", values.score == null)
        values.keeps("score_cleared", earlier, "score")
    }

    // A refused write is a failed write (W2), and changed nothing, not even the time of the last change.
    private fun refused(
        name: String,
        earlier: String,
    ) {
        val before = before(earlier)
        val outcome = put(name, before.values)
        assertTrue("$name: not a refusal of the request", outcome == Outcome.Failure(DataError.InvalidRequest))
        val after = readBack("${name}_read")
        assertTrue("${name}_read holds no entry", after != null)
        assertTrue("${name}_read differs from $earlier in its values", after!!.values == before.values)
        assertTrue("${name}_read differs from $earlier in updated_at", after.updatedAt == before.updatedAt)
    }

    @Test
    fun `score_refused - a score above 10 is refused with 400, and the entry is as it was`() =
        refused("score_refused", "score_cleared_read")

    @Test
    fun `status_refused - a status MAL does not know is refused with 400, and the entry is as it was`() =
        refused("status_refused", "score_refused_read")

    @Test
    fun `entry_full - the title's own answer gives the entry the last read-back gave, its title read whole`() {
        val last = before("status_refused_read")
        network.unexpected.clear()
        val entry = readBack("entry_full")
        assertTrue("entry_full holds no entry", entry != null)
        assertTrue("entry_full: title repaired", network.unexpected.isEmpty())
        assertTrue("entry_full: another title", entry!!.anime.id == title.id)
        assertTrue("entry_full differs from status_refused_read in its values", entry.values == last.values)
        assertTrue("entry_full differs from status_refused_read in updated_at", entry.updatedAt == last.updatedAt)
    }

    @Test
    fun `removed - a removal is answered with no entry, and the title is off the list after it`() {
        assertTrue("removed: not a removal", put("removed", null) == Outcome.Success(null))
        assertTrue("removed_read holds an entry", readBack("removed_read") == null)
    }

    @Test
    fun `removed_again - removing a title that is not on the list is answered as a removal`() {
        assertTrue("removed_again: not a removal", put("removed_again", null) == Outcome.Success(null))
        val again = OwnerRecordings.named("removed_again")
        val first = OwnerRecordings.named("removed")
        assertTrue("removed_again is answered unlike removed", again.status == first.status && again.body == first.body)
    }

    @Test
    fun `own_list - every entry of the user's own pages written back unchanged carries each value as MAL gave it`() {
        for (name in (1..3).map { "own_list_$it" }) {
            val answer = OwnerRecordings.named(name)
            val page = OwnerRecordings.read(name) { MalJson.parseToJsonElement(answer.body).jsonObject }
            assertWrittenAsGiven(name, page)
        }
    }

    private companion object {
        const val ABSENT = "entry_absent"
        val PLANNED = ListValues(ListStatus.Planned, 0, null, null, null)
    }
}

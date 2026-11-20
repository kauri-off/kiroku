package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.anime.recordedJson
import io.github.kauri_off.kiroku.data.anime.toAnime
import io.github.kauri_off.kiroku.data.anime.toNode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

// The list's mapper on the recorded public pages, one value altered inside a test for a shape no page holds.
class MalListPageTest {
    private val page = recordedJson("list_public_1")

    // The first entry: completed, scored 8, 12 watched, both dates given.
    private val first: ListEntry = page.toListPage(Repairs()).entries.first()

    private fun firstWith(
        key: String,
        value: JsonElement?,
    ): ListEntry =
        page
            .edited(listOf("data", "0", "list_status", key), value)
            .toListPage(Repairs())
            .entries
            .first()

    private val wrongShapes = listOf(null, JsonNull, JsonPrimitive(true), JsonArray(emptyList()), JsonObject(emptyMap()))

    @Test
    fun `every recorded entry is mapped with its title and its values, in the page's order`() {
        for (name in listOf("list_public_1", "list_public_2", "list_public_3")) {
            val recorded = recordedJson(name).getValue("data").jsonArray.map { it.jsonObject }
            val entries = recordedJson(name).toListPage(Repairs()).entries
            assertEquals(
                recorded.map {
                    it
                        .getValue("node")
                        .jsonObject
                        .toNode()
                        .toAnime(Repairs())
                },
                entries.map { it.anime },
            )
        }
        assertEquals(ListStatus.Completed, first.status)
        assertEquals(8, first.values.score)
        assertEquals(12, first.values.watchedEpisodes)
        assertEquals(PartialDate.Day(LocalDate.parse("2026-08-31")), first.values.startDate)
        assertEquals(PartialDate.Day(LocalDate.parse("2026-10-07")), first.values.finishDate)
        assertEquals(Instant.parse("2026-10-07T11:45:03Z"), first.updatedAt)
    }

    @Test
    fun `MAL's list status codes are the app's statuses`() {
        val statuses = (1..3).flatMap { recordedJson("list_public_$it").toListPage(Repairs()).entries.map(ListEntry::status) }.toSet()
        assertEquals(setOf(ListStatus.Completed, ListStatus.OnHold, ListStatus.Planned, ListStatus.Watching), statuses)
        assertEquals(ListStatus.Dropped, firstWith("status", JsonPrimitive("dropped")).status)
    }

    @Test
    fun `a score of 0 is no score, and an entry without a start or finish date has none`() {
        val entries = page.toListPage(Repairs()).entries.associateBy { it.anime.id.value }
        val unscored = entries.getValue(41457).values
        assertNull(unscored.score)
        assertEquals(PartialDate.Day(LocalDate.parse("2023-07-27")), unscored.startDate)
        assertNull(unscored.finishDate)
        assertNull(entries.getValue(32998).values.startDate)
    }

    @Test
    fun `a partial date keeps its precision`() {
        assertEquals(PartialDate.Month(YearMonth.of(2021, 6)), firstWith("start_date", JsonPrimitive("2021-06")).values.startDate)
        assertEquals(PartialDate.OnlyYear(Year.of(2021)), firstWith("finish_date", JsonPrimitive("2021")).values.finishDate)
    }

    @Test
    fun `a score that is missing, of another type or out of range is no score, and one in quotes is the number`() {
        val absent =
            wrongShapes + listOf(JsonPrimitive("eight"), JsonPrimitive(7.5), JsonPrimitive(11), JsonPrimitive(-1), JsonPrimitive(""))
        for (value in absent) assertNull("$value", firstWith("score", value).values.score)
        assertEquals(9, firstWith("score", JsonPrimitive("9")).values.score)
        assertEquals(10, firstWith("score", JsonPrimitive(10)).values.score)
        assertEquals(1, firstWith("score", JsonPrimitive(1)).values.score)
    }

    @Test
    fun `a watched count that is missing, of another type or negative is none watched, and one in quotes is the number`() {
        val absent = wrongShapes + listOf(JsonPrimitive("twelve"), JsonPrimitive(2.5), JsonPrimitive(-1), JsonPrimitive(""))
        for (value in absent) assertEquals("$value", 0, firstWith("num_episodes_watched", value).values.watchedEpisodes)
        assertEquals(5, firstWith("num_episodes_watched", JsonPrimitive("5")).values.watchedEpisodes)
        assertEquals(40, firstWith("num_episodes_watched", JsonPrimitive(40)).values.watchedEpisodes)
    }

    // The first entry's list status with a rewatch count added, which no recorded page was asked for.
    private fun rewatched(
        value: JsonElement,
        repairs: Repairs = Repairs(),
    ): ListEntry {
        val values =
            page
                .getValue("data")
                .jsonArray[0]
                .jsonObject
                .getValue("list_status")
                .jsonObject
        val added = JsonObject(values + ("num_times_rewatched" to value))
        return page
            .edited(listOf("data", "0", "list_status"), added)
            .toListPage(repairs)
            .entries
            .first()
    }

    // The shape is the API reference's: an integer in every entry's list status.
    @Test
    fun `a rewatch count is MAL's, one missing or MAL's empty value is none, and an unreadable one is none and noted`() {
        assertEquals(0, first.values.timesRewatched)
        assertEquals(first.values.copy(timesRewatched = 3), rewatched(JsonPrimitive(3)).values)
        assertEquals(2, rewatched(JsonPrimitive("2")).values.timesRewatched)
        for (value in listOf(JsonNull, JsonPrimitive(""))) {
            val repairs = Repairs()
            assertEquals("$value", 0, rewatched(value, repairs).values.timesRewatched)
            assertEquals("$value", emptySet<String>(), repairs.fields)
        }
        val unreadable = listOf(JsonPrimitive("twice"), JsonPrimitive(1.5), JsonPrimitive(-1), JsonPrimitive(true), JsonArray(emptyList()))
        for (value in unreadable) {
            val repairs = Repairs()
            assertEquals("$value", 0, rewatched(value, repairs).values.timesRewatched)
            assertEquals("$value", setOf("list_status.num_times_rewatched"), repairs.fields)
        }
    }

    @Test
    fun `a date that is missing, of another type or does not parse is no date`() {
        val absent =
            wrongShapes +
                listOf(
                    JsonPrimitive(2021),
                    JsonPrimitive(""),
                    JsonPrimitive("2021-13-01"),
                    JsonPrimitive("2021-02-30"),
                    JsonPrimitive("soon"),
                )
        for (value in absent) {
            assertNull("$value", firstWith("start_date", value).values.startDate)
            assertNull("$value", firstWith("finish_date", value).values.finishDate)
        }
    }

    @Test
    fun `a change time that is missing, of another type or does not parse sorts as the oldest`() {
        val absent =
            wrongShapes + listOf(JsonPrimitive(1_671_104_703), JsonPrimitive(""), JsonPrimitive("2022-12-15"), JsonPrimitive("yesterday"))
        for (value in absent) assertEquals("$value", NEVER_UPDATED, firstWith("updated_at", value).updatedAt)
        assertEquals(Instant.parse("2022-12-15T02:45:03Z"), firstWith("updated_at", JsonPrimitive("2022-12-15T11:45:03+09:00")).updatedAt)
    }

    // The owner's rule: nothing fails because MAL sends a value the app does not know.
    @Test
    fun `a list status the app does not know keeps MAL's code and is noted, and every other entry is as it was`() {
        val recorded = page.toListPage(Repairs()).entries
        for (code in listOf("rewatching", "Completed", "on hold")) {
            val repairs = Repairs()
            val mapped = page.edited(listOf("data", "0", "list_status", "status"), JsonPrimitive(code)).toListPage(repairs)
            assertEquals(code, EntryStatus.Unrecognized(code), mapped.entries.first().status)
            assertEquals(code, recorded.first().values.copy(status = EntryStatus.Unrecognized(code)), mapped.entries.first().values)
            assertEquals(code, recorded.drop(1), mapped.entries.drop(1))
            assertEquals(code, setOf("list_status.status"), repairs.fields)
        }
    }

    @Test
    fun `no list status, or one that is not a code, fails the page and leaves no entry out`() {
        val unknown = wrongShapes + listOf(JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive(1))
        for (value in unknown) assertThrows("$value", IllegalArgumentException::class.java) { firstWith("status", value) }
        for (values in wrongShapes) {
            val edited = page.edited(listOf("data", "0", "list_status"), values)
            assertThrows("$values", IllegalArgumentException::class.java) { edited.toListPage(Repairs()) }
        }
    }

    @Test
    fun `an entry whose title has no usable id fails the page and leaves no entry out`() {
        for (id in listOf(null, JsonNull, JsonPrimitive("x"), JsonPrimitive(0), JsonPrimitive(-3))) {
            val edited = page.edited(listOf("data", "0", "node", "id"), id)
            assertThrows("$id", IllegalArgumentException::class.java) { edited.toListPage(Repairs()) }
        }
    }

    @Test
    fun `only a page without a next link is the last, and of a next link only its offset is read`() {
        assertEquals(10, page.toListPage(Repairs()).next)
        assertNull(recordedJson("list_public_3").toListPage(Repairs()).next)
        assertNull(page.edited(listOf("paging", "next")).toListPage(Repairs()).next)
        assertNull(page.edited(listOf("paging", "next"), JsonNull).toListPage(Repairs()).next)
        assertNull(page.edited(listOf("paging")).toListPage(Repairs()).next)
        assertNull(page.edited(listOf("paging"), JsonNull).toListPage(Repairs()).next)
        val link = "https://api.myanimelist.net/v2/users/public-user/animelist"
        val unreadable =
            listOf(JsonPrimitive(10), JsonPrimitive("not a link"), JsonPrimitive(""), JsonObject(emptyMap())) +
                listOf(
                    link,
                    "$link?offset=",
                    "$link?offset=x",
                    "$link?offset=-10",
                    "$link?offset=1.5",
                    "$link?start=10",
                ).map(::JsonPrimitive)
        for (next in unreadable) {
            assertThrows("$next", IllegalStateException::class.java) { page.edited(listOf("paging", "next"), next).toListPage(Repairs()) }
        }
        assertThrows(IllegalStateException::class.java) { page.edited(listOf("paging"), JsonPrimitive("next")).toListPage(Repairs()) }
    }

    @Test
    fun `a page without its entries fails, and is never a page with none`() {
        for (data in listOf(null, JsonNull, JsonObject(emptyMap()), JsonPrimitive("[]"))) {
            assertThrows("$data", SerializationException::class.java) { page.edited(listOf("data"), data).toListPage(Repairs()) }
            assertThrows(
                "$data",
                SerializationException::class.java,
            ) { recordedJson("list_public_3").edited(listOf("data"), data).toListPage(Repairs()) }
        }
    }

    @Test
    fun `a watched count on a planned entry is kept as MAL sent it`() {
        val planned =
            page
                .edited(listOf("data", "0", "list_status", "status"), JsonPrimitive("plan_to_watch"))
                .edited(listOf("data", "0", "list_status", "num_episodes_watched"), JsonPrimitive(40))
                .toListPage(Repairs())
                .entries
                .first()
                .values
        assertEquals(ListStatus.Planned, planned.status)
        assertEquals(40, planned.watchedEpisodes)
    }

    @Test
    fun `a finish date before the start is kept as MAL sent it`() {
        val backwards = firstWith("start_date", JsonPrimitive("2026-11-01")).values
        assertEquals(PartialDate.Day(LocalDate.parse("2026-11-01")), backwards.startDate)
        assertEquals(PartialDate.Day(LocalDate.parse("2026-10-07")), backwards.finishDate)
    }

    // The owner's rule: an unreadable value is absent but not silent; MAL's own empty values are not repairs.
    @Test
    fun `an entry's value read as absent because it is unreadable is noted by its field, and MAL's own empty values are not`() {
        fun repaired(
            key: String,
            value: JsonElement?,
        ): Set<String> = Repairs().also { page.edited(listOf("data", "0", "list_status", key), value).toListPage(it) }.fields

        val unreadable =
            mapOf(
                "score" to listOf(JsonPrimitive("eight"), JsonPrimitive(7.5), JsonPrimitive(11), JsonPrimitive(-1), JsonPrimitive(true)),
                "num_episodes_watched" to listOf(JsonPrimitive("twelve"), JsonPrimitive(-1), JsonArray(emptyList())),
                "start_date" to listOf(JsonPrimitive("2021-13-01"), JsonPrimitive(2021), JsonObject(emptyMap())),
                "finish_date" to listOf(JsonPrimitive("soon")),
                "updated_at" to listOf(JsonPrimitive("yesterday"), JsonPrimitive(1_671_104_703)),
            )
        for ((key, values) in unreadable) {
            for (value in values) assertEquals("$key $value", setOf("list_status.$key"), repaired(key, value))
        }
        for (key in unreadable.keys) {
            for (value in listOf(null, JsonNull, JsonPrimitive(""))) assertEquals("$key $value", emptySet<String>(), repaired(key, value))
        }
        assertEquals(emptySet<String>(), repaired("score", JsonPrimitive(0)))
        assertEquals(
            setOf("node.mean"),
            Repairs().also { page.edited(listOf("data", "0", "node", "mean"), JsonPrimitive("broken")).toListPage(it) }.fields,
        )
        for (name in listOf("list_public_1", "list_public_2", "list_public_3")) {
            assertEquals(name, emptySet<String>(), recordedJson(name).toListPage(Repairs()).repaired)
        }
    }
}

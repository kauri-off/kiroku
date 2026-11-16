package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SentValuesTest {
    private val start = PartialDate.Day(LocalDate.of(2025, 12, 1))
    private val today = PartialDate.Day(LocalDate.of(2026, 1, 14))

    private fun watching(watched: Int) = ListValues(ListStatus.Watching, watched, 8, start, null)

    private fun SentValues?.fields() = this?.fields.orEmpty()

    @Test
    fun `a step carries the count alone, and W7's changes with it`() {
        assertEquals(setOf(ListField.WatchedEpisodes), watching(10).sentOver(watching(9), watching(9)).fields())
        val completed = watching(12).copy(status = ListStatus.Completed, finishDate = today)
        assertEquals(
            setOf(ListField.Status, ListField.WatchedEpisodes, ListField.FinishDate),
            completed.sentOver(watching(11), watching(11)).fields(),
        )
    }

    @Test
    fun `a value the edit did not change is not sent, also where MAL holds another one`() {
        val website = watching(9).copy(score = 3, finishDate = today)
        assertEquals(setOf(ListField.WatchedEpisodes), watching(10).sentOver(watching(9), website).fields())
    }

    @Test
    fun `a value MAL holds already is not sent, and a write with nothing to carry is none`() {
        assertNull(watching(10).sentOver(watching(9), watching(10)))
        assertNull(watching(9).sentOver(watching(9), watching(9)))
        assertEquals(setOf(ListField.Score), watching(9).copy(score = null).sentOver(watching(9), watching(9)).fields())
    }

    @Test
    fun `with nothing known it was made from, every value MAL does not hold is sent`() {
        val saved = watching(10).copy(score = null)
        assertEquals(setOf(ListField.WatchedEpisodes, ListField.Score), saved.sentOver(null, watching(9)).fields())
    }

    @Test
    fun `a status the app does not know is never sent`() {
        val unknown = watching(10).copy(status = EntryStatus.Unrecognized("rewatching"))
        assertEquals(setOf(ListField.WatchedEpisodes), unknown.sentOver(watching(9), watching(9)).fields())
        assertEquals(setOf(ListField.WatchedEpisodes), unknown.sentOver(null, watching(9)).fields())
    }

    @Test
    fun `a new entry carries its status and each value an empty entry lacks`() {
        val planned = ListValues(ListStatus.Planned, 0, null, null, null)
        assertEquals(setOf(ListField.Status), planned.sentOver(null, null).fields())
        assertEquals(
            setOf(ListField.Status, ListField.WatchedEpisodes, ListField.StartDate),
            watching(3).copy(score = null).sentOver(planned, null).fields(),
        )
    }

    @Test
    fun `MAL keeps what a write leaves out, and a new entry starts empty`() {
        val website = watching(9).copy(score = 3)
        val step = checkNotNull(watching(10).sentOver(watching(9), website))
        assertEquals(website.copy(watchedEpisodes = 10), step.over(website))
        val planned = ListValues(ListStatus.Planned, 0, null, null, null)
        assertEquals(planned, checkNotNull(planned.sentOver(null, null)).over(null))
    }

    @Test
    fun `values sent are equal when the values carried are, whatever the others hold`() {
        val one = SentValues(watching(10), setOf(ListField.WatchedEpisodes))
        assertEquals(one, SentValues(watching(10).copy(score = 2), setOf(ListField.WatchedEpisodes)))
        assertEquals(one.hashCode(), SentValues(watching(10).copy(score = 2), setOf(ListField.WatchedEpisodes)).hashCode())
    }
}

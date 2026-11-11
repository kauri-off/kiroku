package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ListWriteTest {
    private val anime = FixtureAnime.oneShortOfTotal.copy(malEpisodes = 12)
    private val today = LocalDate.of(2026, 1, 14)
    private val day = PartialDate.Day(today)
    private val earlier = PartialDate.Day(LocalDate.of(2025, 12, 1))

    private fun entry(values: ListValues) = ListEntry(anime, values, Instant.EPOCH)

    private fun watching(watched: Int) = ListValues(ListStatus.Watching, watched, 8, earlier, null)

    @Test
    fun `mark watched applies to the entry as it is when sent`() {
        val write = ListWrite.MarkWatched(anime, 10)
        assertEquals(watching(10), write.applyTo(entry(watching(9)), today))
    }

    @Test
    fun `mark watched never lowers the count or touches other values`() {
        val write = ListWrite.MarkWatched(anime, 10)
        val newer = watching(11).copy(score = 9)
        assertEquals(newer, write.applyTo(entry(newer), today))
        assertEquals(watching(10), write.applyTo(entry(watching(10)), today))
    }

    @Test
    fun `mark watched does nothing for an entry that is gone`() {
        assertNull(ListWrite.MarkWatched(anime, 10).applyTo(null, today))
        assertNull(ListWrite.MarkNextWatched(anime).applyTo(null, today))
    }

    @Test
    fun `mark watched follows W7 and goes past MAL's total`() {
        val completed = ListValues(ListStatus.Completed, 12, 8, earlier, day)
        assertEquals(completed, ListWrite.MarkWatched(anime, 12).applyTo(entry(watching(11)), today))
        assertEquals(completed.copy(watchedEpisodes = 13), ListWrite.MarkWatched(anime, 13).applyTo(entry(watching(12)), today))
    }

    @Test
    fun `N9 mark watched never jumps over an unwatched episode`() {
        assertEquals(watching(8), ListWrite.MarkWatched(anime, 10).applyTo(entry(watching(8)), today))
        assertEquals(watching(11), ListWrite.MarkWatched(anime, 13).applyTo(entry(watching(11)), today))
    }

    @Test
    fun `two increments sent before either is applied both count`() {
        val write = ListWrite.MarkNextWatched(anime)
        val first = checkNotNull(write.applyTo(entry(watching(9)), today))
        assertEquals(watching(11), write.applyTo(entry(first), today))
    }

    @Test
    fun `an increment has no ceiling`() {
        val completed = ListValues(ListStatus.Completed, 12, 8, earlier, earlier)
        var values = completed
        repeat(20) { values = checkNotNull(ListWrite.MarkNextWatched(anime).applyTo(entry(values), today)) }
        assertEquals(completed.copy(watchedEpisodes = 32), values)
    }

    @Test
    fun `an increment on a planned title starts it`() {
        val planned = ListValues(ListStatus.Planned, 0, null, null, null)
        assertEquals(
            ListValues(ListStatus.Watching, 1, null, day, null),
            ListWrite.MarkNextWatched(anime).applyTo(entry(planned), today),
        )
    }

    @Test
    fun `save and remove ignore the current values`() {
        assertEquals(watching(3), ListWrite.Save(anime, watching(3)).applyTo(entry(watching(9)), today))
        assertNull(ListWrite.Remove(anime).applyTo(entry(watching(9)), today))
    }

    @Test
    fun `a typed count keeps every other value as it is when sent, and follows W7 and W9`() {
        val newer = watching(9).copy(score = 6)
        assertEquals(newer.copy(watchedEpisodes = 10), ListWrite.SetWatched(anime, 10).applyTo(entry(newer), today))
        val completed = ListValues(ListStatus.Completed, 30, 8, earlier, day)
        assertEquals(completed, ListWrite.SetWatched(anime, 30).applyTo(entry(watching(9)), today))
        assertNull(ListWrite.SetWatched(anime, 10).applyTo(null, today))
    }

    @Test
    fun `steps sent before either is applied both count, and a step down stops at zero`() {
        val up = ListWrite.StepWatched(anime, 1)
        val first = checkNotNull(up.applyTo(entry(watching(9)), today))
        assertEquals(watching(11), up.applyTo(entry(first), today))
        val down = ListWrite.StepWatched(anime, -1)
        assertEquals(watching(8), down.applyTo(entry(watching(9)), today))
        assertEquals(watching(0), down.applyTo(entry(watching(0)), today))
    }

    @Test
    fun `W3 a change's Undo puts back every value it changed, and removes an entry it added`() {
        val before = entry(watching(11))
        val after = entry(ListValues(ListStatus.Completed, 12, 8, earlier, day))
        val change = ListChange(before, after)
        assertEquals(true, change.completed)
        assertEquals(ListWrite.Save(anime, watching(11)), change.undo)
        assertEquals(false, ListChange(entry(watching(10)), before).completed)
        assertEquals(ListWrite.Remove(anime), ListChange(null, before).undo)
        assertEquals(ListWrite.Save(anime, watching(11)), ListChange(before, null).undo)
    }
}

package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.SignInId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun undo(
        change: ListChange,
        now: ListValues?,
    ) = change.undo.applyTo(now?.let(::entry), today)

    private val list = SignInId("a")

    private val completing =
        ListChange(entry(watching(11)), entry(ListValues(ListStatus.Completed, 12, 8, earlier, day)), stepped = true, list)

    @Test
    fun `W3 Undo of a step takes back that step only, after later steps`() {
        val change = ListChange(entry(watching(12)), entry(watching(13)), stepped = true, list)
        assertEquals(watching(14), undo(change, watching(15)))
        assertEquals(watching(12), undo(change, watching(13)))
        assertEquals(watching(0), undo(ListChange(entry(watching(0)), entry(watching(3)), stepped = true, list), watching(1)))
    }

    @Test
    fun `W3 Undo of a completing step keeps a score set afterwards and puts back the status and the date it filled`() {
        assertTrue(completing.completed)
        val scored = ListValues(ListStatus.Completed, 12, 9, earlier, day)
        assertEquals(watching(11).copy(score = 9), undo(completing, scored))
        assertEquals(watching(12), undo(completing, scored.copy(watchedEpisodes = 13, score = 8)))
    }

    @Test
    fun `W3 a value changed since the undone edit is kept`() {
        val onHold = ListValues(ListStatus.OnHold, 12, 8, earlier, earlier)
        assertEquals(onHold.copy(watchedEpisodes = 11), undo(completing, onHold))
    }

    @Test
    fun `W3 a typed count goes back only while it is still the count`() {
        val typed = ListChange(entry(watching(9)), entry(watching(20)), stepped = false, list)
        assertEquals(watching(9).copy(score = 6), undo(typed, watching(20).copy(score = 6)))
        assertEquals(watching(21), undo(typed, watching(21)))
    }

    @Test
    fun `W3 Undo removes an entry the change added and brings back one it removed`() {
        val added = ListChange(null, entry(watching(0)), stepped = false, list)
        assertNull(undo(added, watching(2)))
        val removed = ListChange(entry(watching(4)), null, stepped = false, list)
        assertEquals(watching(4), undo(removed, null))
        assertEquals(watching(1), undo(removed, watching(1)))
        assertNull(undo(completing, null))
    }

    @Test
    fun `only plus one, the stepper and mark watched are steps`() {
        assertEquals(
            listOf(true, true, true, false, false, false),
            listOf(
                ListWrite.MarkNextWatched(anime),
                ListWrite.MarkWatched(anime, 3),
                ListWrite.StepWatched(anime, -1),
                ListWrite.SetWatched(anime, 3),
                ListWrite.Save(anime, watching(3)),
                ListWrite.Remove(anime),
            ).map { it.steps },
        )
    }
}

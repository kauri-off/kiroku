package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.Broadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

class BroadcastSlotTest {
    private val saturdayLate = Broadcast(DayOfWeek.SATURDAY, LocalTime.of(23, 30))

    @Test
    fun theSlotIsTakenInJapanTime() {
        // Wed 14 Jan 2026 18:00 JST.
        val now = Instant.parse("2026-01-14T09:00:00Z")
        assertEquals(Instant.parse("2026-01-17T14:30:00Z"), saturdayLate.nextStart(now))
    }

    @Test
    fun aSlotStartingNowIsTheNextOneAndAPassedOneIsNextWeeks() {
        val start = Instant.parse("2026-01-17T14:30:00Z")
        assertEquals(start, saturdayLate.nextStart(start))
        assertEquals(Instant.parse("2026-01-24T14:30:00Z"), saturdayLate.nextStart(start.plusSeconds(1)))
    }

    @Test
    fun theJapaneseDayCanBeAnotherDayInUtc() {
        val sundayEarly = Broadcast(DayOfWeek.SUNDAY, LocalTime.of(1, 0))
        assertEquals(Instant.parse("2026-01-17T16:00:00Z"), sundayEarly.nextStart(Instant.parse("2026-01-14T09:00:00Z")))
    }

    @Test
    fun aSlotWithoutATimeHasNoStart() {
        assertNull(Broadcast(DayOfWeek.SUNDAY, null).nextStart(Instant.EPOCH))
    }
}

package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.AiringStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class FreshnessTest {
    private val at = Instant.parse("2026-11-14T12:00:00Z")

    private fun after(age: Duration): Instant = at + age.toJavaDuration()

    @Test
    fun `the list is fresh for fifteen minutes after its sync`() {
        assertFalse(listStale(at, at))
        assertFalse(listStale(at, after(15.minutes - 1.milliseconds)))
        assertTrue(listStale(at, after(15.minutes)))
    }

    @Test
    fun `a list never synced or owed a sync is stale`() = assertTrue(listStale(null, at))

    @Test
    fun `a sync time after now, from a clock set back, is stale`() = assertTrue(listStale(after(1.minutes), at))

    @Test
    fun `a finished or coming title's details are fresh for a day`() {
        for (anime in listOf(FixtureAnime.plannedFinished, FixtureAnime.premiereDate)) {
            assertFalse(detailsStale(anime, at, after(24.hours - 1.milliseconds)))
            assertTrue(detailsStale(anime, at, after(24.hours)))
        }
    }

    @Test
    fun `an airing title's details are fresh for six hours`() {
        assertFalse(detailsStale(FixtureAnime.exactToWatch, at, after(6.hours - 1.milliseconds)))
        assertTrue(detailsStale(FixtureAnime.exactToWatch, at, after(6.hours)))
    }

    // No rule in Airing.kt can tell an unknown status from airing; this one compares with Airing, so it can.
    @Test
    fun `a title whose airing status the app does not know is fresh as an airing one`() {
        val unknown = FixtureAnime.exactToWatch.copy(airingStatus = AiringStatus.Unrecognized("on_hiatus"))
        assertTrue(detailsStale(unknown, at, after(7.hours)))
        for (age in listOf(0.hours, 6.hours - 1.milliseconds, 6.hours, 7.hours, 24.hours)) {
            assertEquals(age.toString(), detailsStale(FixtureAnime.exactToWatch, at, after(age)), detailsStale(unknown, at, after(age)))
        }
    }

    @Test
    fun `details never fetched, or fetched after now, are stale`() {
        assertTrue(detailsStale(FixtureAnime.plannedFinished, null, at))
        assertTrue(detailsStale(null, null, at))
        assertTrue(detailsStale(FixtureAnime.plannedFinished, after(1.minutes), at))
    }

    @Test
    fun `airing data is fresh for as long as the list, and stale when never fetched or fetched after now`() {
        assertEquals(Freshness.list, Freshness.airing)
        assertFalse(airingStale(at, at))
        assertFalse(airingStale(at, after(15.minutes - 1.milliseconds)))
        assertTrue(airingStale(at, after(15.minutes)))
        assertTrue(airingStale(null, at))
        assertTrue(airingStale(after(1.minutes), at))
    }

    @Test
    fun `a title not known yet has a finished title's time`() {
        assertFalse(detailsStale(null, at, after(24.hours - 1.milliseconds)))
        assertTrue(detailsStale(null, at, after(24.hours)))
    }
}

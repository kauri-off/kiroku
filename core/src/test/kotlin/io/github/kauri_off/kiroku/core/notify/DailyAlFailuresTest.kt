package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.notify.NotifyCases.day
import io.github.kauri_off.kiroku.core.notify.NotifyCases.entry
import io.github.kauri_off.kiroku.core.notify.NotifyCases.followed
import io.github.kauri_off.kiroku.core.notify.NotifyCases.hour
import io.github.kauri_off.kiroku.core.notify.NotifyCases.title
import io.github.kauri_off.kiroku.core.notify.NotifyCases.week
import io.github.kauri_off.kiroku.core.notify.NotifyCases.weekly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

// Section 5.1: each failure of dailyAL, as the case that must not happen here.
class DailyAlFailuresTest {
    private val none = emptyList<Pair<AnimeId, Int>>()

    // A title announced week by week until [change], made an hour after episode 3 aired: nothing of it is announced after.
    private fun assertNoLongerAnnounced(change: (Device) -> Unit) {
        val device = followed(entry())
        assertEquals(listOf(1, 2, 3), device.walk(week(0), week(2)).announced.map { it.second })
        assertEquals(week(3), device.run(week(2)).nextAt)
        change(device)
        val after = device.run(week(2).plus(hour))
        assertEquals(setOf(title.id), after.withdrawn)
        assertTrue(after.silent)
        assertTrue(device.walk(week(2).plus(hour), week(13)).all { it.silent })
        assertEquals(listOf(1, 2, 3), device.episodes())
    }

    private fun moved(
        episode: Int,
        to: (Instant) -> Instant,
    ): Map<AnimeId, AiringSchedule> =
        mapOf(
            title.id to
                weekly().let { it.copy(episodes = it.episodes.map { e -> if (e.number == episode) e.copy(airsAt = to(e.airsAt)) else e }) },
        )

    @Test
    fun `a dropped title is not announced`() = assertNoLongerAnnounced { it.status(ListStatus.Dropped) }

    @Test
    fun `a completed title is not announced`() = assertNoLongerAnnounced { it.status(ListStatus.Completed) }

    @Test
    fun `an on-hold title is not announced`() = assertNoLongerAnnounced { it.status(ListStatus.OnHold) }

    @Test
    fun `a removed title is not announced`() = assertNoLongerAnnounced { it.entries = emptyList() }

    @Test
    fun `an episode postponed after it was planned is announced when it airs, and not when it was planned`() {
        val device = followed(entry())
        device.walk(week(0), week(2))
        assertEquals(week(3), device.run(week(2).plus(day)).nextAt)
        // Episode 4 skips a week, and the episodes after it keep their times.
        device.schedules = moved(4) { it.plus(day.multipliedBy(7)) }
        // Two episodes with one time are one announcement to come, of the higher.
        assertEquals(listOf(Coming(title, 5, week(4), week(4))), device.run(week(2).plus(day.multipliedBy(2))).coming)
        assertEquals(none, device.walk(week(3).minus(hour), week(4).minus(hour)).announced)
        assertEquals(listOf(title.id to 5), device.run(week(4)).announced)
        // Postponed alone, with the next episode in its place a week later.
        val alone = followed(entry())
        alone.walk(week(0), week(2))
        alone.schedules =
            mapOf(
                title.id to
                    weekly().let {
                        it.copy(
                            episodes =
                                it.episodes.map { e ->
                                    if (e.number <
                                        4
                                    ) {
                                        e
                                    } else {
                                        e.copy(airsAt = e.airsAt.plus(day.multipliedBy(7)))
                                    }
                                },
                        )
                    },
            )
        assertEquals(none, alone.walk(week(2).plus(hour), week(4).minus(hour)).announced)
        assertEquals(listOf(title.id to 4), alone.run(week(4)).announced)
        assertEquals(listOf(1, 2, 3, 4, 5), alone.also { it.walk(week(4), week(5)) }.episodes())
    }

    @Test
    fun `an episode moved to another weekday is announced on its new day, and only then`() {
        for (days in listOf(-4L, 3L)) {
            val device = followed(entry())
            device.walk(week(0), week(2))
            device.schedules = moved(4) { it.plus(day.multipliedBy(days)) }
            val newDay = week(3).plus(day.multipliedBy(days))
            assertTrue(newDay.atZone(FixtureTime.japan).dayOfWeek != DayOfWeek.SATURDAY)
            assertEquals(newDay, device.run(week(2).plus(hour)).nextAt)
            assertEquals(none, device.walk(week(2).plus(hour), newDay.minus(hour)).announced)
            assertEquals(listOf(title.id to 4), device.run(newDay).announced)
            assertEquals(none, device.walk(newDay, week(4).minus(hour)).announced)
            assertEquals(listOf(title.id to 5), device.run(week(4)).announced)
        }
    }

    @Test
    fun `a show that has run for years and belongs to no season is announced`() {
        val set = Fixtures.reference
        val show = FixtureAnime.unknownTotal
        assertEquals(2004, (show.startDate as PartialDate.Day).date.year)
        assertNull(show.malEpisodes)
        val entry = set.entryFor(show.id)!!
        assertEquals(1118, entry.values.watchedEpisodes)
        val device = Device(listOf(entry), set.schedules)
        val airsAt = FixtureTime.japan("2026-01-17T18:00")
        assertEquals(listOf(Coming(show, 1122, airsAt, airsAt)), device.run(set.now).coming)
        assertEquals(listOf(Due(Announcement(show, 1122, toWatch = 4, marks = 1119), airsAt, airsAt)), device.run(airsAt).due)
    }
}

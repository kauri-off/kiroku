package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.Inexact
import io.github.kauri_off.kiroku.core.airing.SCHEDULE_STOPPED_AFTER
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.airing.titleAiring
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.notify.NotifyCases.day
import io.github.kauri_off.kiroku.core.notify.NotifyCases.entry
import io.github.kauri_off.kiroku.core.notify.NotifyCases.hour
import io.github.kauri_off.kiroku.core.notify.NotifyCases.other
import io.github.kauri_off.kiroku.core.notify.NotifyCases.otherAfter
import io.github.kauri_off.kiroku.core.notify.NotifyCases.title
import io.github.kauri_off.kiroku.core.notify.NotifyCases.week
import io.github.kauri_off.kiroku.core.notify.NotifyCases.weekly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import kotlin.time.toJavaDuration

class NotifiableTest {
    private val finished = title.copy(airingStatus = AiringStatus.Finished)

    private fun only(schedule: AiringSchedule?) = listOfNotNull(schedule?.let { title.id to it }).toMap()

    @Test
    fun `an episode is announced from its notification time on, with what is to watch and the episode Mark watched marks`() {
        val device = followed(entry())
        val before = device.run(week(0).minusNanos(1))
        assertEquals(emptyList<Due>(), before.due)
        assertEquals(listOf(Coming(title, 1, week(0), week(0))), before.coming)
        assertEquals(listOf(Due(Announcement(title, 1, toWatch = 1, marks = 1), week(0), week(0))), device.run(week(0)).due)

        val behind = Device(listOf(entry(watched = 3))).also { it.run(week(5).minus(day)) }
        assertEquals(listOf(Due(Announcement(title, 6, toWatch = 3, marks = 4), week(5), week(5))), behind.run(week(5)).due)
        // A1: the count is the one the List shows.
        val shown = titleAiring(title, behind.entries.single().values, weekly(), week(5)).phase as AiringPhase.Aired
        assertEquals(AiredCount.Exact(3), shown.toWatch)
    }

    @Test
    fun `the notification time is the air time and the delay`() {
        for (delay in NotificationDelay.entries) {
            val at = week(0).plus(delay.duration.toJavaDuration())
            val device = followed(entry(), settings = NotificationSettings(delay = delay))
            val before = device.run(at.minusNanos(1))
            assertEquals(delay.name, emptyList<Due>(), before.due)
            assertEquals(delay.name, at, before.nextAt)
            assertEquals(delay.name, listOf(Due(Announcement(title, 1, 1, 1), airedAt = week(0), notifyAt = at)), device.run(at).due)
        }
        assertEquals(listOf(0L, 30L, 60L, 180L), NotificationDelay.entries.map { it.duration.inWholeMinutes })
        assertEquals(NotificationSettings(on = true, alsoPlanned = false, delay = NotificationDelay.None), NotificationSettings())
    }

    @Test
    fun `A1 the count to watch is the aired count's, also while a later episode has aired and is not yet due`() {
        // Episode 2 airs an hour after episode 1.
        val pair = AiringSchedule(listOf(ScheduledEpisode(1, week(0)), ScheduledEpisode(2, week(0).plus(hour))), 12)
        val device = followed(entry(), schedules = only(pair), settings = NotificationSettings(delay = NotificationDelay.ThreeHours))
        val first = week(0).plus(hour.multipliedBy(3))
        assertEquals(listOf(Due(Announcement(title, 1, toWatch = 2, marks = 1), week(0), first)), device.run(first).due)
        assertEquals(
            AiredCount.Exact(2),
            (titleAiring(title, device.entries.single().values, pair, first).phase as AiringPhase.Aired).toWatch,
        )
        val second = Due(Announcement(title, 2, toWatch = 2, marks = 1), week(0).plus(hour), first.plus(hour))
        assertEquals(listOf(second), device.run(first.plus(hour)).due)
    }

    @Test
    fun `N4 a title that is dropped, completed, on hold or in a status the app does not know is not announced`() {
        val statuses = listOf(ListStatus.Dropped, ListStatus.Completed, ListStatus.OnHold, Fixtures.unknownListStatus)
        for (status in statuses) {
            for (settings in listOf(NotificationSettings(), NotificationSettings(alsoPlanned = true))) {
                val device = followed(entry(status = status, watched = 2), settings = settings)
                assertTrue("$status", device.walk(week(-1), week(13)).all { it.silent })
            }
        }
        val watching = followed(entry())
        assertEquals((1..12).toList(), watching.walk(week(-1), week(13)).announced.map { it.second })
    }

    @Test
    fun `N4 a planned title is announced only when that is enabled`() {
        val off = followed(entry(status = ListStatus.Planned))
        assertTrue(off.walk(week(-1), week(13)).all { it.silent })
        val on = followed(entry(status = ListStatus.Planned), settings = NotificationSettings(alsoPlanned = true))
        assertEquals((1..12).toList(), on.walk(week(-1), week(13)).announced.map { it.second })
    }

    @Test
    fun `N4 a title that is not on the list is not announced, whatever is stored of it`() {
        val device = followed(entry(other))
        assertEquals(setOf(title.id, other.id), device.schedules.keys)
        assertEquals(emptyList<Int>(), device.also { it.walk(week(-1), week(13)) }.episodes(title.id))
        assertEquals((1..12).toList(), device.episodes(other.id))
        assertTrue(followed().walk(week(-1), week(13)).all { it.silent })
    }

    @Test
    fun `N4 an episode the user has watched is not announced`() {
        // W9: a count ahead of what has aired.
        val device = followed(entry(watched = 5))
        val early = device.walk(week(-1), week(4).plus(day))
        assertEquals(emptyList<Pair<AnimeId, Int>>(), early.announced)
        assertEquals(week(5), early.last().nextAt)
        assertEquals(listOf(Announcement(title, 6, toWatch = 1, marks = 6)), device.run(week(5)).due.map { it.announcement })
        device.watched(9)
        assertEquals(listOf(10, 11, 12), device.walk(week(5), week(13)).announced.map { it.second })
    }

    @Test
    fun `N4 a count that is an estimate or unknown announces nothing, whatever its reason`() {
        for (reason in Inexact.entries) {
            val (schedule, from) =
                when (reason) {
                    Inexact.NoSchedule -> null to week(-1)
                    Inexact.NumberedDifferently -> weekly(13..24, from = 0, total = 24) to week(-1)
                    Inexact.TotalsDisagree -> weekly(1..13, total = 13) to week(-1)
                    Inexact.ScheduleStopped -> weekly(1..6) to week(5).plus(SCHEDULE_STOPPED_AFTER)
                    // Such a schedule is in nothing a notification reads; given to the rule all the same, it announces nothing.
                    Inexact.NumberingDoubted -> weekly().copy(doubtedOver = 2) to week(-1)
                }
            val device = Device(listOf(entry()), only(schedule))
            var now = from
            while (now.isBefore(week(14))) {
                val airing = airingAt(title, schedule, now)
                assertEquals("$reason at $now", reason, airing.inexact)
                assertTrue("$reason at $now", airing.aired is AiredCount.Estimated)
                assertTrue("$reason at $now", device.run(now).silent)
                now = now.plus(hour.multipliedBy(6))
            }
        }
        val undated = title.copy(startDate = null, broadcast = null)
        assertEquals(AiredCount.Unknown, airingAt(undated, null, week(3)).aired)
        assertTrue(followed(entry(undated), schedules = emptyMap()).walk(week(-1), week(13)).all { it.silent })
        // The same title with a schedule that agrees with its own total is announced until the schedule stops.
        val stopping = followed(entry(), schedules = only(weekly(1..6)))
        assertEquals((1..6).toList(), stopping.walk(week(-1), week(14)).announced.map { it.second })
    }

    @Test
    fun `N4 an episode whose air time is not known is never announced`() {
        val gap = AiringSchedule(listOf(1, 2, 5, 6).map { ScheduledEpisode(it, week(it - 1)) }, 12)
        val device = followed(entry(), schedules = only(gap))
        val runs = device.walk(week(-1), week(8))
        assertEquals(listOf(1, 2, 5, 6), runs.announced.map { it.second })
        assertEquals(setOf(1, 2, 5, 6), runs.flatMap { it.coming }.map { it.episode }.toSet())
        // All of a finished title has aired, and nothing stored says when its finale did.
        assertEquals(AiredCount.Exact(12), airingAt(finished, weekly(1..11), week(11)).aired)
        val untimed = followed(entry(finished, watched = 11), schedules = only(weekly(1..11)))
        assertTrue(untimed.walk(week(-1), week(13)).all { it.silent })
    }

    @Test
    fun `N6 several due episodes of one title give one announcement, for the highest of them`() {
        val device = followed(entry(), entry(other))
        val now = week(2).plus(hour)
        val result = device.run(now)
        assertEquals(
            listOf(
                Due(Announcement(other, 2, toWatch = 2, marks = 1), week(1).plus(otherAfter), week(1).plus(otherAfter)),
                Due(Announcement(title, 3, toWatch = 3, marks = 1), week(2), week(2)),
            ),
            result.due,
        )
        // The lower ones are not announced after it.
        assertEquals(emptyList<Pair<AnimeId, Int>>(), device.walk(now, week(2).plus(otherAfter).minus(hour)).announced)
        assertEquals(listOf(other.id to 3), device.run(week(2).plus(otherAfter)).announced)
        assertEquals(listOf(title.id to 4), device.run(week(3)).announced)
    }

    @Test
    fun `N7 an announcement is withdrawn once its title is not one notifications are for`() {
        val changes: Map<String, (Device) -> Unit> =
            mapOf(
                "dropped" to { it.status(ListStatus.Dropped, at = week(0).plus(hour)) },
                "completed" to { it.status(ListStatus.Completed, at = week(0).plus(hour)) },
                "on hold" to { it.status(ListStatus.OnHold, at = week(0).plus(hour)) },
                "planned" to { it.status(ListStatus.Planned, at = week(0).plus(hour)) },
                "a status the app does not know" to { it.status(Fixtures.unknownListStatus, at = week(0).plus(hour)) },
                "removed" to { it.remove() },
                "notifications off" to { it.settings = it.settings.copy(on = false) },
            )
        for ((name, change) in changes) {
            val device = followed(entry())
            device.run(week(0))
            assertEquals(name, listOf(Announcement(title, 1, 1, 1)), device.run(week(0).plus(hour)).standing)
            change(device)
            val after = device.run(week(0).plus(hour.multipliedBy(2)))
            assertEquals(name, setOf(title.id), after.withdrawn)
            assertEquals(name, emptyList<Announcement>(), after.standing)
            assertTrue(name, after.silent)
            // It is withdrawn once.
            assertEquals(name, emptySet<AnimeId>(), device.run(week(0).plus(hour.multipliedBy(3))).withdrawn)
        }
        val planned = followed(entry(status = ListStatus.Planned), settings = NotificationSettings(alsoPlanned = true))
        planned.run(week(0))
        planned.settings = NotificationSettings(alsoPlanned = false)
        assertEquals(setOf(title.id), planned.run(week(0).plus(hour)).withdrawn)
    }

    @Test
    fun `N7 an announcement is withdrawn once its episode is watched, and until then says what is left`() {
        val device = followed(entry())
        assertEquals(listOf(Announcement(title, 3, toWatch = 3, marks = 1)), device.run(week(2)).due.map { it.announcement })
        val later = week(2).plus(hour)
        device.watched(1)
        assertEquals(listOf(Announcement(title, 3, toWatch = 2, marks = 2)), device.run(later).standing)
        device.watched(2)
        val last = device.run(later)
        assertEquals(listOf(Announcement(title, 3, toWatch = 1, marks = 3)), last.standing)
        assertEquals(emptySet<AnimeId>(), last.withdrawn)
        device.watched(3)
        val watched = device.run(later)
        assertEquals(setOf(title.id), watched.withdrawn)
        assertEquals(emptyList<Announcement>(), watched.standing)
        // Counting back does not post it again.
        device.watched(0)
        val back = device.run(later)
        assertEquals(emptyList<Announcement>(), back.standing)
        assertEquals(emptyList<Due>(), back.due)
    }

    @Test
    fun `N7 an announcement stands while its schedule is lost or its count is an estimate, and is not made again`() {
        for (schedule in listOf(null, weekly(1..13, total = 13), weekly().copy(doubtedOver = 2))) {
            val device = followed(entry())
            device.run(week(2))
            device.schedules = only(schedule)
            val after = device.run(week(2).plus(hour))
            assertEquals(emptySet<AnimeId>(), after.withdrawn)
            assertEquals(listOf(Announcement(title, 3, toWatch = null, marks = 1)), after.standing)
            assertEquals(emptyList<Due>(), after.due)
            // The schedule is back, as it was or with a later episode aired: the announcement reads by it again.
            device.schedules = only(weekly())
            assertEquals(listOf(Announcement(title, 3, toWatch = 3, marks = 1)), device.run(week(2).plus(hour.multipliedBy(2))).standing)
            assertEquals(listOf(3, 4), device.also { it.walk(week(2).plus(hour.multipliedBy(2)), week(3)) }.episodes())
        }
    }

    @Test
    fun `a newer episode takes the place of the standing announcement, and nothing is withdrawn`() {
        val device = followed(entry())
        device.run(week(0))
        val next = device.run(week(1))
        assertEquals(listOf(Announcement(title, 2, toWatch = 2, marks = 1)), next.due.map { it.announcement })
        assertEquals(emptyList<Announcement>(), next.standing)
        assertEquals(emptySet<AnimeId>(), next.withdrawn)
        assertEquals(
            TitleRecord(Followed(ListStatus.Watching, week(0).minus(day)), Announced(2, week(1)), standing = true),
            device.record.titles[title.id],
        )
    }

    @Test
    fun `A13 a finale is announced after its show is marked finished`() {
        // Marked before the finale's air time.
        val early = followed(entry(watched = 10))
        early.run(week(10))
        early.entries = listOf(entry(finished, watched = 10))
        assertEquals(listOf(Coming(finished, 12, week(11), week(11))), early.run(week(11).minus(hour)).coming)
        assertEquals(listOf(Due(Announcement(finished, 12, toWatch = 2, marks = 11), week(11), week(11))), early.run(week(11)).due)

        // Marked between the air time and a delayed notification time.
        val delayed = followed(entry(watched = 11), settings = NotificationSettings(delay = NotificationDelay.ThreeHours))
        assertEquals(week(11).plus(hour.multipliedBy(3)), delayed.run(week(11)).nextAt)
        delayed.entries = listOf(entry(finished, watched = 11))
        assertEquals(listOf(finished.id to 12), delayed.run(week(11).plus(hour.multipliedBy(3))).announced)

        // Marked before anything ran after the finale.
        val late = followed(entry(watched = 11))
        late.run(week(10))
        late.entries = listOf(entry(finished, watched = 11))
        assertEquals(listOf(finished.id to 12), late.run(week(11).plus(day.multipliedBy(2))).announced)
    }

    @Test
    fun `a finished title announces nothing but its finale`() {
        val device = followed(entry(finished, watched = 2))
        val runs = device.walk(week(-1), week(11).minus(hour))
        assertEquals(emptyList<Pair<AnimeId, Int>>(), runs.announced)
        assertTrue(runs.all { it.coming == listOf(Coming(finished, 12, week(11), week(11))) })
        assertEquals(listOf(finished.id to 12), device.run(week(11)).announced)
    }

    @Test
    fun `N2 an episode is announced up to three days after its notification time, and not after`() {
        assertEquals(Duration.ofDays(3), announcedNoLaterThan.toJavaDuration())
        for (delay in NotificationDelay.entries) {
            val settings = NotificationSettings(delay = delay)
            val limit = week(0).plus(delay.duration.toJavaDuration()).plus(Duration.ofDays(3))
            assertEquals(delay.name, listOf(title.id to 1), followed(entry(), settings = settings).run(limit).announced)
            val late = followed(entry(), settings = settings)
            assertEquals(delay.name, emptyList<Pair<AnimeId, Int>>(), late.run(limit.plusNanos(1)).announced)
            assertEquals(delay.name, emptyList<Pair<AnimeId, Int>>(), late.walk(limit.plus(hour), week(1).minus(hour)).announced)
            assertEquals(delay.name, listOf(title.id to 2), late.run(week(1).plus(delay.duration.toJavaDuration())).announced)
        }
        // An episode that is too late does not hold back a lower one that is not.
        val swapped = AiringSchedule(listOf(ScheduledEpisode(1, week(0)), ScheduledEpisode(2, week(0).minus(day.multipliedBy(4)))), 12)
        val device = Device(listOf(entry()), only(swapped)).also { it.run(week(0).minus(day.multipliedBy(5))) }
        assertEquals(listOf(Due(Announcement(title, 1, toWatch = 2, marks = 1), week(0), week(0))), device.run(week(0)).due)
        assertTrue(device.walk(week(0), week(1)).all { it.silent })
    }

    @Test
    fun `N8 the next time is the earliest notification time still to come, and none when nothing is`() {
        val device = followed(entry(), entry(other))
        val first = device.run(week(0).minus(hour))
        val aired = week(0).plus(otherAfter)
        assertEquals(listOf(Coming(title, 1, week(0), week(0)), Coming(other, 1, aired, aired)), first.coming)
        assertEquals(week(0), first.nextAt)
        assertEquals(aired, device.run(week(0)).nextAt)

        // The delay alone moves it, also for an episode that has aired and is not yet due.
        device.settings = NotificationSettings(delay = NotificationDelay.ThreeHours)
        assertEquals(aired.plus(hour.multipliedBy(3)), device.run(aired.minus(hour)).nextAt)
        assertEquals(Coming(other, 1, aired, aired.plus(hour.multipliedBy(3))), device.run(aired.plus(hour)).coming.first())
        device.settings = NotificationSettings(delay = NotificationDelay.HalfHour)
        val sooner = device.run(aired.plus(hour))
        assertEquals(listOf(other.id to 1), sooner.announced)
        assertEquals(week(1).plus(hour.dividedBy(2)), sooner.nextAt)

        // A count ahead of the schedule waits for the first episode not watched.
        device.settings = NotificationSettings()
        device.watched(4)
        device.watched(4, other.id)
        assertEquals(week(4), device.run(week(1)).nextAt)

        device.settings = NotificationSettings(on = false)
        assertNull(device.run(week(1)).nextAt)
        device.settings = NotificationSettings()
        assertEquals(week(4), device.run(week(1)).nextAt)
        assertNull(device.run(week(11).plus(otherAfter)).nextAt)
    }

    @Test
    fun `N8 a run at each next time announces the episode that time named, and a run just before it announces nothing`() {
        val set = Fixtures.reference
        for (delay in NotificationDelay.entries) {
            val device = Device(set.entries, set.schedules, NotificationSettings(alsoPlanned = true, delay = delay))
            var result = device.run(set.now)
            var steps = 0
            while (true) {
                val next = result.coming.firstOrNull() ?: break
                assertEquals(next.airsAt.plus(delay.duration.toJavaDuration()), next.notifyAt)
                assertEquals(emptyList<Due>(), device.run(next.notifyAt.minusNanos(1)).due)
                result = device.run(next.notifyAt)
                assertTrue("${next.anime.titles.romaji} ${next.episode}", (next.anime.id to next.episode) in result.announced)
                steps++
            }
            assertTrue("$steps", steps > 40)
        }
    }
}

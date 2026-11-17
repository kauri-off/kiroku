package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.notify.NotifyCases.day
import io.github.kauri_off.kiroku.core.notify.NotifyCases.entry
import io.github.kauri_off.kiroku.core.notify.NotifyCases.followed
import io.github.kauri_off.kiroku.core.notify.NotifyCases.hour
import io.github.kauri_off.kiroku.core.notify.NotifyCases.title
import io.github.kauri_off.kiroku.core.notify.NotifyCases.week
import io.github.kauri_off.kiroku.core.notify.NotifyCases.weekly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.TimeZone
import kotlin.random.Random
import kotlin.time.toJavaDuration

class AnnouncedOnceTest {
    private val none = emptyList<Pair<AnimeId, Int>>()

    private fun shifted(
        schedule: AiringSchedule,
        by: Duration,
        from: Int = 1,
    ) = schedule.copy(episodes = schedule.episodes.map { if (it.number < from) it else it.copy(airsAt = it.airsAt.plus(by)) })

    // What a result must be, whatever led to it.
    private fun assertSound(
        result: Notifications,
        now: Instant,
    ) {
        val due = result.due.map { it.announcement.anime.id }
        val standing = result.standing.map { it.anime.id }
        assertEquals(due.size, due.toSet().size)
        assertEquals(emptySet<AnimeId>(), due.toSet() intersect standing.toSet())
        assertEquals(emptySet<AnimeId>(), result.withdrawn intersect (due + standing).toSet())
        for (one in result.due) {
            assertTrue(!one.notifyAt.isAfter(now) && !now.isAfter(one.notifyAt.plus(announcedNoLaterThan.toJavaDuration())))
            assertTrue(one.announcement.marks <= one.announcement.episode && one.announcement.toWatch!! >= 1)
        }
        assertTrue(result.coming.all { it.notifyAt.isAfter(now) })
        assertEquals(result.coming.sortedBy { it.notifyAt }, result.coming)
        assertEquals(
            result.coming.size,
            result.coming
                .map { it.anime.id }
                .toSet()
                .size,
        )
        assertEquals(
            result.record.titles
                .filterValues { it.standing }
                .keys,
            (due + standing).toSet(),
        )
    }

    @Test
    fun `N1 the rule applied and recorded at any sequence of instants announces each episode at most once`() {
        val set = Fixtures.reference
        val statuses = ListStatus.entries + Fixtures.unknownListStatus
        var announced = 0
        var changes = 0
        repeat(60) { round ->
            val random = Random(round)
            val device = Device(set.entries, set.schedules, NotificationSettings(alsoPlanned = random.nextBoolean()))
            var now = set.now.minus(day.multipliedBy(14))
            repeat(500) {
                // The clock moves on by up to a day and a half, stands still, or is set back.
                now =
                    when (random.nextInt(12)) {
                        0 -> now
                        1 -> now.minusSeconds(random.nextLong(3 * 3600))
                        else -> now.plusSeconds(random.nextLong(36 * 3600))
                    }
                val id = set.entries[random.nextInt(set.entries.size)].anime.id
                val event = random.nextInt(16)
                if (event < 10) changes++
                when (event) {
                    0 -> device.settings = device.settings.copy(delay = NotificationDelay.entries.random(random))
                    1 -> device.settings = device.settings.copy(on = !device.settings.on)
                    2 -> device.settings = device.settings.copy(alsoPlanned = !device.settings.alsoPlanned)
                    // A refresh that moves a title's episodes, all or the later ones, by up to four hours either way.
                    3, 4 ->
                        device.schedules[id]?.let { schedule ->
                            val from = if (random.nextBoolean()) 1 else schedule.episodes.random(random).number
                            device.schedules += id to shifted(schedule, Duration.ofMinutes(random.nextLong(-240, 240)), from)
                        }
                    5 -> device.schedules = if (random.nextBoolean()) device.schedules - id else set.schedules
                    6 -> device.status(statuses.random(random), id)
                    7 -> device.change(id) { it.copy(watchedEpisodes = (it.watchedEpisodes + random.nextInt(-2, 3)).coerceAtLeast(0)) }
                    // A download that leaves the title out, or one that brings back what was left out.
                    8 -> device.entries = device.entries.filter { it.anime.id != id }
                    9 -> device.entries = set.entries
                }
                assertSound(device.run(now), now)
            }
            assertEquals("round $round", device.announced.size, device.announced.toSet().size)
            announced += device.announced.size
        }
        assertTrue("$announced", announced > 1000)
        assertTrue("$changes", changes > 10000)
    }

    @Test
    fun `N1 a refresh between an episode's air time and its notification announces it once`() {
        val delayed = week(2).plus(hour.multipliedBy(3))
        val device = followed(entry(watched = 2), settings = NotificationSettings(delay = NotificationDelay.ThreeHours))
        assertEquals(delayed, device.run(week(2).plus(hour)).nextAt)
        // The episode is moved half an hour on, then back.
        device.schedules = mapOf(title.id to shifted(weekly(), hour.dividedBy(2), from = 3))
        assertEquals(delayed.plus(hour.dividedBy(2)), device.run(week(2).plus(hour.multipliedBy(2))).nextAt)
        assertEquals(none, device.run(delayed).announced)
        assertEquals(listOf(title.id to 3), device.run(delayed.plus(hour.dividedBy(2))).announced)
        device.schedules = mapOf(title.id to weekly())
        assertEquals(none, device.walk(delayed.plus(hour.dividedBy(2)), week(3)).announced)
        assertEquals(listOf(3, 4), device.also { it.run(week(3).plus(hour.multipliedBy(3))) }.episodes())
    }

    @Test
    fun `N1 a refresh after the announcement that moves its episode later does not announce it again`() {
        val device = followed(entry(watched = 2))
        assertEquals(listOf(title.id to 3), device.run(week(2)).announced)
        for (by in listOf(hour, day, day.multipliedBy(7))) {
            device.schedules = mapOf(title.id to shifted(weekly(), by, from = 3))
            val moved = week(2).plus(by)
            assertEquals("$by", none, device.walk(moved.minus(hour), moved.plus(hour)).announced)
        }
        assertEquals(listOf(3), device.episodes())
    }

    @Test
    fun `N1 a changed delay announces no episode twice`() {
        val device = followed(entry(watched = 2))
        assertEquals(listOf(title.id to 3), device.run(week(2)).announced)
        for (delay in NotificationDelay.entries.reversed()) {
            device.settings = NotificationSettings(delay = delay)
            assertEquals(delay.name, none, device.walk(week(2), week(2).plus(hour.multipliedBy(4))).announced)
        }
        assertEquals(listOf(3, 4), device.also { it.run(week(3)) }.episodes())
    }

    @Test
    fun `N1 a run long after the notification time, again at the same instant, or over the record a new process reads announces once`() {
        val device = followed(entry(watched = 2))
        val late = week(2).plus(hour.multipliedBy(30))
        assertEquals(listOf(title.id to 3), device.run(late).announced)
        assertEquals(none, device.run(late).announced)
        val restarted = Device(device.entries, device.schedules, device.settings, device.record)
        assertEquals(none, restarted.walk(late, week(3).minus(hour)).announced)
        assertEquals(listOf(title.id to 4), restarted.run(week(3)).announced)
        // Two runs that read the same record give the same announcement, which the record holds once.
        val first = followed(entry(watched = 2))
        val record = first.record
        assertEquals(first.run(late), notificationsAt(first.entries, first.schedules, first.settings, record, late))
    }

    @Test
    fun `no result depends on the time zone`() {
        val set = Fixtures.reference
        val zones = listOf("UTC", "Asia/Tokyo", "America/Los_Angeles", "Pacific/Kiritimati", "Europe/Moscow")
        val original = TimeZone.getDefault()
        try {
            // The whole of two months, in one zone, in each other, and with the zone changed at every run.
            fun results(zone: (Int) -> String): List<Notifications> {
                val device =
                    Device(set.entries, set.schedules, NotificationSettings(alsoPlanned = true, delay = NotificationDelay.HalfHour))
                return List(60 * 8) { step ->
                    TimeZone.setDefault(TimeZone.getTimeZone(zone(step)))
                    device.run(set.now.plus(hour.multipliedBy(3L * step)))
                }
            }
            val expected = results { zones.first() }
            assertTrue(expected.announced.size > 30)
            for (zone in zones) assertEquals(zone, expected, results { zone })
            assertEquals(expected, results { zones[it % zones.size] })
        } finally {
            TimeZone.setDefault(original)
        }
    }
}

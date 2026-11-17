package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.Inexact
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.notify.NotifyCases.day
import io.github.kauri_off.kiroku.core.notify.NotifyCases.entry
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

    // The test's own account of one episode, kept from what the recorded runs found and announced, never from the record.
    private class Seen {
        // The air times it was announced for, and whether a run has found it not aired since it was announced.
        val told = mutableSetOf<Instant>()
        var unaired = false
    }

    @Test
    fun `N1, N2 at any sequence of instants an episode is announced once for each air time it has, and none that is due is left out`() {
        val set = Fixtures.reference
        val statuses = ListStatus.entries + Fixtures.unknownListStatus
        var announced = 0
        var changes = 0
        var owed = 0
        var again = 0
        var afresh = 0
        repeat(60) { round ->
            val random = Random(round)
            val device = Device(set.entries, set.schedules, NotificationSettings(alsoPlanned = random.nextBoolean()))
            val seen = mutableMapOf<AnimeId, MutableMap<Int, Seen>>()
            val cut = mutableSetOf<Pair<AnimeId, Int>>()
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
                // A change of the list is made in the app now, was made on the website up to two days ago, or has a time ahead of the clock.
                val changedAt =
                    when (random.nextInt(4)) {
                        0 -> now.minusSeconds(random.nextLong(48 * 3600))
                        1 -> now.plusSeconds(3600)
                        else -> now
                    }
                val event = random.nextInt(16)
                if (event < 12) changes++
                when (event) {
                    0 -> device.settings = device.settings.copy(delay = NotificationDelay.entries.random(random))
                    1 -> device.settings = device.settings.copy(on = !device.settings.on)
                    2 -> device.settings = device.settings.copy(alsoPlanned = !device.settings.alsoPlanned)
                    // A refresh that moves a title's episodes, all or the later ones: by up to four hours either way, or a week on.
                    3, 4 ->
                        device.schedules[id]?.let { schedule ->
                            val from = if (random.nextBoolean()) 1 else schedule.episodes.random(random).number
                            val by = if (random.nextInt(3) == 0) day.multipliedBy(7) else Duration.ofMinutes(random.nextLong(-240, 240))
                            device.schedules += id to shifted(schedule, by, from)
                        }
                    5 -> device.schedules = if (random.nextBoolean()) device.schedules - id else set.schedules
                    6 -> device.status(statuses.random(random), changedAt, id)
                    7 -> {
                        val watched =
                            device.entries
                                .firstOrNull { it.anime.id == id }
                                ?.values
                                ?.watchedEpisodes ?: 0
                        device.watched((watched + random.nextInt(-2, 3)).coerceAtLeast(0), id)
                    }
                    // A title removed, and one added again as it was or anew.
                    8 -> device.remove(id)
                    9 ->
                        if (device.entries.none { it.anime.id == id }) {
                            val entry = set.entryFor(id)!!
                            device.entries += if (random.nextBoolean()) entry else entry.copy(updatedAt = changedAt)
                        }
                    // The episode that aired last, of each title, is postponed with those after it: by an hour, or by a week.
                    10, 11 ->
                        device.schedules =
                            device.schedules.mapValues { (_, schedule) ->
                                val last = schedule.episodes.filter { !it.airsAt.isAfter(now) }.maxOfOrNull { it.number }
                                if (last == null) schedule else shifted(schedule, if (event == 10) hour else day.multipliedBy(7), last)
                            }
                }
                // One run in eight is cut off after it posted: it records nothing, and the next run posts the same again.
                val cutOff = random.nextInt(8) == 0
                val result = device.run(now, cutOff)
                assertSound(result, now)
                val delay =
                    device.settings.delay.duration
                        .toJavaDuration()
                for (entry in device.entries) {
                    val title = entry.anime.id
                    val since =
                        result.record.titles[title]
                            ?.followed
                            ?.since ?: continue
                    val episodes = seen.getOrPut(title) { mutableMapOf() }
                    val airing = airingAt(entry.anime, device.schedules[title], now)
                    // What was announced of the title: its highest episode, which told of those below it too, and the latest air time.
                    val highest = episodes.filterValues { it.told.isNotEmpty() }.keys.maxOrNull() ?: 0
                    val latest = episodes.values.flatMap { it.told }.maxOrNull()
                    // N6: an episode below one that was announced and stands as aired was told of with it.
                    val stood = episodes.filterValues { it.told.isNotEmpty() && !it.unaired }.keys.maxOrNull() ?: 0
                    // N2: owed is an episode not told of yet, or one found not aired since that has aired at a later time than any told.
                    val due =
                        airing.airTimes
                            .filter {
                                val at = it.airsAt.plus(delay)
                                val limit = at.plus(announcedNoLaterThan.toJavaDuration())
                                val again = episodes[it.number]?.unaired == true && latest != null && it.airsAt.isAfter(latest)
                                val owes = it.number > highest || (again && it.number > stood)
                                it.number > entry.values.watchedEpisodes &&
                                    owes &&
                                    at.isAfter(since) &&
                                    !at.isAfter(now) &&
                                    !now.isAfter(limit)
                            }.maxByOrNull { it.number }
                    val name = "round $round, ${entry.anime.titles.romaji} at $now"
                    assertEquals(
                        name,
                        listOfNotNull(due?.let { Triple(title, it.number, it.airsAt) }),
                        result.airings.filter {
                            it.first ==
                                title
                        },
                    )
                    if (due != null) owed++
                    if (cutOff) continue
                    if (due != null) {
                        // It is told of, and with it those below it.
                        if (due.number <= highest) afresh++
                        episodes.getOrPut(due.number) { Seen() }.told += due.airsAt
                        episodes.filterKeys { it <= due.number }.values.forEach { it.unaired = false }
                    }
                    val count = (airing.aired as? AiredCount.Exact)?.value ?: continue
                    // An exact count below an episode that was told of: the run has found it not aired.
                    for (number in count + 1..maxOf(highest, due?.number ?: 0)) episodes.getOrPut(number) { Seen() }.unaired = true
                    // An episode found aired no later than an air time told: that airing was announced, whatever is corrected later.
                    for ((number, episode) in episodes) {
                        val airedAt = airing.airTimes.firstOrNull { it.number == number }?.airsAt ?: continue
                        if (number <= count && latest != null && !airedAt.isAfter(latest) && !airedAt.isAfter(now)) episode.unaired = false
                    }
                }
                if (cutOff) cut += result.announced else again += result.announced.count { cut.remove(it) }
            }
            assertEquals("round $round", device.airings.size, device.airings.toSet().size)
            announced += device.airings.size
        }
        assertTrue("$announced", announced > 800)
        assertTrue("$owed", owed > 800)
        assertTrue("$again", again > 50)
        assertTrue("$afresh", afresh > 100)
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
    fun `no result of the rule or of the calculator depends on the time zone`() {
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

            // The calculator where a day's start decides: MAL's dates are Japan's, wherever the device is.
            val dayStart = week(0).minus(Duration.ofMinutes(23 * 60 + 30))
            // Episode 7 half an hour into the fourth week from the start date is as far ahead as A4 lets a first stored episode be.
            val first = dayStart.plus(day.multipliedBy(21)).plus(hour.dividedBy(2))
            val stored = AiringSchedule((7..12).map { ScheduledEpisode(it, first.plus(day.multipliedBy(7L * (it - 7)))) }, 12)
            val earlier = stored.copy(episodes = stored.episodes.map { it.copy(airsAt = it.airsAt.minus(hour)) })
            val notYetAired = title.copy(airingStatus = AiringStatus.NotYetAired)

            fun calculated() =
                List(120) { step ->
                    val now = week(-1).plus(hour.multipliedBy(6L * step))
                    listOf(
                        airingAt(notYetAired, null, now),
                        airingAt(notYetAired.copy(broadcast = null), null, now),
                        airingAt(title, stored, now),
                    )
                }
            TimeZone.setDefault(TimeZone.getTimeZone(zones.first()))
            val counted = calculated()
            for (zone in zones) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(zone, null, airingAt(title, stored, first.plus(day)).inexact)
                assertEquals(zone, Inexact.NumberedDifferently, airingAt(title, earlier, first.plus(day)).inexact)
                assertEquals(zone, counted, calculated())
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }
}

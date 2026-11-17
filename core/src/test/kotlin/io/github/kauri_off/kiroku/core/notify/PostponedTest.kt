package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
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

// N1, N7. Episode 3 is announced at week(2) from the stored schedule, and AniList then gives it another time.
class PostponedTest {
    private val followedFrom = Followed(ListStatus.Watching, week(0).minus(day))
    private val none = emptyList<Pair<AnimeId, Int>>()
    private val week = Duration.ofDays(7)
    private val halfHour = Duration.ofMinutes(30)

    // The schedule with episode 3 and every one after it [by] later.
    private fun moved(by: Duration): Map<AnimeId, AiringSchedule> =
        mapOf(
            title.id to
                weekly().let { it.copy(episodes = it.episodes.map { e -> if (e.number < 3) e else e.copy(airsAt = e.airsAt.plus(by)) }) },
        )

    private fun announced(): Device = followed(entry(watched = 2)).also { assertEquals(listOf(title.id to 3), it.run(week(2)).announced) }

    private fun third(airedAt: Instant) = Due(Announcement(title, 3, toWatch = 1, marks = 3), airedAt, airedAt)

    @Test
    fun `an episode postponed by a week after it was announced is withdrawn, and announced again when it airs`() {
        val device = announced()
        device.schedules = moved(week)
        // The exact count is 2 again: nothing says episode 3 has aired, and "Mark watched" would mark it.
        val found = device.run(week(2).plus(hour))
        assertEquals(setOf(title.id), found.withdrawn)
        assertEquals(emptyList<Announcement>(), found.standing)
        assertEquals(emptyList<Due>(), found.due)
        assertEquals(listOf(Coming(title, 3, week(3), week(3))), found.coming)
        assertEquals(TitleRecord(followedFrom, Announced(3, week(2)), takenBackTo = 2), device.record.titles[title.id])
        assertTrue(
            device.walk(week(2).plus(hour), week(3).minus(hour)).all {
                it.due.isEmpty() &&
                    it.withdrawn.isEmpty() &&
                    it.standing.isEmpty()
            },
        )
        assertEquals(listOf(third(week(3))), device.run(week(3)).due)
        assertEquals(TitleRecord(followedFrom, Announced(3, week(3)), standing = true), device.record.titles[title.id])
        // Once for each air time it had, and the next episode as ever.
        assertEquals(listOf(3, 3, 4), device.also { it.walk(week(3), week(4)) }.episodes())
        assertEquals(listOf(week(2), week(3), week(4)), device.airings.map { it.third })
    }

    @Test
    fun `a correction of minutes withdraws only an announcement that a run finds ahead of its episode`() {
        // Found when the episode has aired at its new time too: it is simply still aired.
        val aired = announced()
        aired.schedules = moved(halfHour)
        val after = aired.run(week(2).plus(halfHour.multipliedBy(2)))
        assertEquals(emptySet<AnimeId>(), after.withdrawn)
        assertEquals(listOf(Announcement(title, 3, toWatch = 1, marks = 3)), after.standing)
        assertEquals(TitleRecord(followedFrom, Announced(3, week(2)), standing = true), aired.record.titles[title.id])
        assertEquals(listOf(3, 4), aired.also { it.walk(week(2).plus(hour), week(3).plus(halfHour)) }.episodes())

        // Found before its new time: withdrawn, and announced when that comes.
        val early = announced()
        early.schedules = moved(halfHour)
        val before = early.run(week(2).plus(Duration.ofMinutes(10)))
        assertEquals(setOf(title.id), before.withdrawn)
        assertEquals(week(2).plus(halfHour), before.nextAt)
        assertEquals(listOf(third(week(2).plus(halfHour))), early.run(week(2).plus(halfHour)).due)
        assertEquals(listOf(3, 3, 4), early.also { it.walk(week(2).plus(halfHour), week(3).plus(halfHour)) }.episodes())

        // Moved earlier: it has aired all the same.
        val earlier = announced()
        earlier.schedules = moved(halfHour.negated())
        val moved = earlier.run(week(2).plus(Duration.ofMinutes(10)))
        assertEquals(emptySet<AnimeId>(), moved.withdrawn)
        assertEquals(emptyList<Due>(), moved.due)
        assertEquals(listOf(3, 4), earlier.also { it.walk(week(2), week(3)) }.episodes())
    }

    @Test
    fun `an episode postponed twice is announced once for each time it was given and reached`() {
        // Moved on again before it aired: the first new time announces nothing.
        val device = announced()
        device.schedules = moved(week)
        device.run(week(2).plus(hour))
        device.schedules = moved(week.multipliedBy(2))
        val again = device.run(week(3).minus(hour))
        assertEquals(emptySet<AnimeId>(), again.withdrawn)
        assertEquals(listOf(Coming(title, 3, week(4), week(4))), again.coming)
        assertEquals(none, device.walk(week(3).minus(hour), week(4).minus(hour)).announced)
        assertEquals(listOf(third(week(4))), device.run(week(4)).due)
        assertEquals(listOf(week(2), week(4)), device.airings.map { it.third })

        // Announced at its second time as well, from data as stale, and then moved on.
        val stale = announced()
        stale.schedules = moved(week)
        stale.run(week(2).plus(hour))
        assertEquals(listOf(third(week(3))), stale.run(week(3)).due)
        stale.schedules = moved(week.multipliedBy(2))
        assertEquals(setOf(title.id), stale.run(week(3).plus(hour)).withdrawn)
        assertEquals(TitleRecord(followedFrom, Announced(3, week(3)), takenBackTo = 2), stale.record.titles[title.id])
        assertEquals(listOf(third(week(4))), stale.run(week(4)).due)
        assertEquals(listOf(week(2), week(3), week(4)), stale.airings.map { it.third })
    }

    @Test
    fun `an episode postponed and then given its first time again is not announced twice for that airing`() {
        val device = announced()
        device.schedules = moved(week)
        assertEquals(setOf(title.id), device.run(week(2).plus(hour)).withdrawn)
        device.schedules = moved(Duration.ZERO)
        val back = device.run(week(2).plus(hour.multipliedBy(2)))
        assertEquals(emptyList<Due>(), back.due)
        assertEquals(emptyList<Announcement>(), back.standing)
        assertEquals(none, device.walk(week(2).plus(hour.multipliedBy(2)), week(3).minus(hour)).announced)
        assertEquals(listOf(Due(Announcement(title, 4, toWatch = 2, marks = 3), week(3), week(3))), device.run(week(3)).due)
        assertEquals(TitleRecord(followedFrom, Announced(4, week(3)), standing = true), device.record.titles[title.id])
    }

    @Test
    fun `an episode is not announced twice for one airing after a higher one was announced for an earlier time`() {
        fun with(
            third: Instant,
            fourth: Instant,
        ) = mapOf(
            title.id to
                weekly().let {
                    it.copy(
                        episodes =
                            it.episodes.map { e ->
                                e.copy(
                                    airsAt =
                                        mapOf(3 to third, 4 to fourth)[e.number] ?: e.airsAt,
                                )
                            },
                    )
                },
        )
        val device = announced()
        // AniList adds episode 4 with a time an hour before episode 3's.
        device.schedules = with(week(2), week(2).minus(hour))
        assertEquals(listOf(Triple(title.id, 4, week(2).minus(hour))), device.run(week(2).plus(hour)).airings)
        assertEquals(TitleRecord(followedFrom, Announced(4, week(2)), standing = true), device.record.titles[title.id])
        // Both are postponed, and episode 3 is then given its first time again: that airing was announced.
        val later = week(3).plus(day)
        device.schedules = with(later, later)
        assertEquals(setOf(title.id), device.run(week(2).plus(hour.multipliedBy(2))).withdrawn)
        device.schedules = with(week(2), later)
        assertEquals(none, device.walk(week(2).plus(hour.multipliedBy(3)), later.minus(hour)).announced)
        assertEquals(listOf(Triple(title.id, 4, later)), device.run(later).airings)
    }

    @Test
    fun `an episode postponed is not announced once its title is dropped, and is when its title is watched again`() {
        val dropped = announced()
        dropped.schedules = moved(week)
        assertEquals(setOf(title.id), dropped.run(week(2).plus(hour)).withdrawn)
        dropped.status(ListStatus.Dropped, at = week(2).plus(hour.multipliedBy(2)))
        assertTrue(dropped.walk(week(2).plus(hour.multipliedBy(2)), week(4)).all { it.silent && it.withdrawn.isEmpty() })
        assertEquals(listOf(3), dropped.episodes())

        val resumed = announced()
        resumed.status(ListStatus.Dropped, at = week(2).plus(hour))
        assertEquals(setOf(title.id), resumed.run(week(2).plus(hour)).withdrawn)
        resumed.schedules = moved(week)
        assertTrue(resumed.run(week(2).plus(hour.multipliedBy(2))).silent)
        resumed.status(ListStatus.Watching, at = week(2).plus(hour.multipliedBy(3)))
        val watching = resumed.run(week(2).plus(hour.multipliedBy(3)))
        assertEquals(emptySet<AnimeId>(), watching.withdrawn)
        assertEquals(listOf(Coming(title, 3, week(3), week(3))), watching.coming)
        assertEquals(listOf(third(week(3))), resumed.run(week(3)).due)
    }

    @Test
    fun `two episodes announced as one and then given a time each are announced each at its own`() {
        // Episodes 3 and 4 air together at week(2), by the stored schedule.
        val together =
            mapOf(
                title.id to
                    weekly().let {
                        it.copy(
                            episodes =
                                it.episodes.map { e ->
                                    if (e.number ==
                                        4
                                    ) {
                                        e.copy(airsAt = week(2))
                                    } else {
                                        e
                                    }
                                },
                        )
                    },
            )
        val device = followed(entry(watched = 2), schedules = together)
        assertEquals(listOf(Due(Announcement(title, 4, toWatch = 2, marks = 3), week(2), week(2))), device.run(week(2)).due)
        // Neither has aired: episode 3 comes at week(3) and episode 4 at week(4).
        device.schedules = moved(week)
        assertEquals(setOf(title.id), device.run(week(2).plus(hour)).withdrawn)
        assertEquals(TitleRecord(followedFrom, Announced(4, week(2)), takenBackTo = 2), device.record.titles[title.id])
        assertEquals(listOf(third(week(3))), device.run(week(3)).due)
        assertEquals(TitleRecord(followedFrom, Announced(4, week(3)), takenBackTo = 3, standing = true), device.record.titles[title.id])
        assertEquals(listOf(Announcement(title, 3, toWatch = 1, marks = 3)), device.run(week(3).plus(hour)).standing)
        assertEquals(listOf(Due(Announcement(title, 4, toWatch = 2, marks = 3), week(4), week(4))), device.run(week(4)).due)
        assertEquals(TitleRecord(followedFrom, Announced(4, week(4)), standing = true), device.record.titles[title.id])
        assertEquals(listOf(4, 3, 4), device.episodes())
    }
}

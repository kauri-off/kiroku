package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.notify.NotifyCases.day
import io.github.kauri_off.kiroku.core.notify.NotifyCases.entry
import io.github.kauri_off.kiroku.core.notify.NotifyCases.followed
import io.github.kauri_off.kiroku.core.notify.NotifyCases.hour
import io.github.kauri_off.kiroku.core.notify.NotifyCases.other
import io.github.kauri_off.kiroku.core.notify.NotifyCases.title
import io.github.kauri_off.kiroku.core.notify.NotifyCases.week
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// N5. At [now] three episodes have aired, the third an hour ago; the fourth airs at week(3).
class FollowedFromTest {
    private val now = week(2).plus(hour)
    private val none = emptyList<Pair<AnimeId, Int>>()

    // The title's first announcement after [now] is the first episode whose notification time comes after it.
    private fun assertNothingFromBefore(
        device: Device,
        name: String = "",
    ) {
        val changed = device.run(now)
        assertEquals(name, none, changed.announced.filter { it.first == title.id })
        assertEquals(name, Coming(title, 4, week(3), week(3)), changed.coming.single { it.anime.id == title.id })
        assertEquals(
            name,
            Followed(device.entries.single { it.anime.id == title.id }.status, now),
            device.record.titles[title.id]?.followed,
        )
        device.walk(now, week(3))
        assertEquals(name, listOf(4), device.episodes())
    }

    @Test
    fun `N5 signing in announces nothing from before`() {
        // S9: signed out, the record is forgotten; the list arrives with the sign-in's first download.
        val device = followed(entry(watched = 1))
        device.run(week(1))
        device.record = NotificationRecord()
        device.announced.clear()
        device.entries = emptyList()
        device.run(week(1).plus(day))
        device.entries = listOf(entry())
        assertNothingFromBefore(device)
    }

    @Test
    fun `N5 the first run of a build with notifications over a list an earlier build stored announces nothing from before`() =
        assertNothingFromBefore(Device(listOf(entry(watched = 1))))

    @Test
    fun `N5 turning notifications on announces nothing from before`() {
        val never = Device(listOf(entry()), settings = NotificationSettings(on = false))
        assertEquals(none, never.walk(week(-1), week(2)).announced)
        assertEquals(NotificationRecord(), never.record)
        never.settings = NotificationSettings()
        assertNothingFromBefore(never)

        // Turned off and on again: what came due meanwhile is from before.
        val again = followed(entry())
        again.walk(week(0), week(1))
        again.settings = NotificationSettings(on = false)
        again.run(week(2).minus(hour))
        again.settings = NotificationSettings()
        assertEquals(none, again.run(now).announced)
        assertEquals(listOf(1, 2, 4), again.also { it.walk(now, week(3)) }.episodes())
    }

    @Test
    fun `N5 turning on Also for planned titles announces nothing from before`() {
        val device = followed(entry(status = ListStatus.Planned), entry(other))
        device.settings = NotificationSettings(alsoPlanned = true)
        // The watched title's moment is not this one: its overdue episode is announced.
        assertEquals(listOf(other.id to 2), device.run(now).announced)
        assertEquals(Followed(ListStatus.Watching, week(0).minus(day)), device.record.titles[other.id]?.followed)
        assertNothingFromBefore(device)
    }

    @Test
    fun `N5 adding a title announces nothing from before`() {
        val device = followed(entry(other))
        assertNull(device.record.titles[title.id])
        device.entries += entry()
        assertNothingFromBefore(device)
    }

    @Test
    fun `N5 changing a title's status announces nothing from before`() {
        val others = listOf(ListStatus.OnHold, ListStatus.Dropped, ListStatus.Completed, ListStatus.Planned, Fixtures.unknownListStatus)
        for (from in others) {
            val device = followed(entry(status = from))
            device.status(ListStatus.Watching)
            assertNothingFromBefore(device, "$from")
        }
        // Also between the two statuses notifications are for.
        for ((from, to) in listOf(ListStatus.Planned to ListStatus.Watching, ListStatus.Watching to ListStatus.Planned)) {
            val device = followed(entry(status = from), settings = NotificationSettings(alsoPlanned = true))
            device.status(to)
            assertNothingFromBefore(device, "$from to $to")
        }
        // No change, no new moment: the overdue episode is announced.
        assertEquals(listOf(title.id to 3), followed(entry()).run(now).announced)
    }

    @Test
    fun `N5 a status change that a sync brings counts as one made in the app, and moves no other title's moment`() {
        val device = followed(entry(status = ListStatus.OnHold, watched = 2), entry(other))
        // A download replaces the whole list.
        device.entries = listOf(entry(other).copy(updatedAt = now), entry(status = ListStatus.Watching, watched = 2).copy(updatedAt = now))
        val synced = device.run(now)
        assertEquals(listOf(other.id to 2), synced.announced)
        assertEquals(Followed(ListStatus.Watching, now), synced.record.titles[title.id]?.followed)
        device.walk(now, week(3))
        assertEquals(listOf(4), device.episodes())
    }

    @Test
    fun `N5 with a delay, an episode that aired before the change and is due after it is announced`() {
        val settings = NotificationSettings(delay = NotificationDelay.ThreeHours)
        val notifyAt = week(2).plus(hour.multipliedBy(3))
        val device = followed(entry(status = ListStatus.OnHold), settings = settings)
        device.status(ListStatus.Watching)
        val changed = device.run(now)
        assertEquals(none, changed.announced)
        assertEquals(listOf(Coming(title, 3, week(2), notifyAt)), changed.coming)
        assertEquals(listOf(Due(Announcement(title, 3, toWatch = 3, marks = 1), week(2), notifyAt)), device.run(notifyAt).due)

        // A notification time that is the moment itself is not after it.
        val atOnce = followed(entry(status = ListStatus.OnHold), settings = settings)
        atOnce.status(ListStatus.Watching)
        assertEquals(none, atOnce.run(notifyAt).announced)
        assertEquals(listOf(4), atOnce.also { it.walk(notifyAt, week(3).plus(hour.multipliedBy(3))) }.episodes())
    }

    @Test
    fun `a title left out by a download and brought back is announced neither twice nor late`() {
        // Followed from an hour after episode 2 aired.
        val device = Device(listOf(entry()))
        device.run(week(1).plus(hour))
        val followedFrom = Followed(ListStatus.Watching, week(1).plus(hour))

        // Left out before anything of it was announced, and back: what was from before its moment still is.
        device.entries = emptyList()
        assertTrue(device.run(week(1).plus(hour.multipliedBy(2))).silent)
        assertEquals(TitleRecord(followedFrom, announced = null), device.record.titles[title.id])
        device.entries = listOf(entry())
        assertEquals(none, device.run(week(1).plus(hour.multipliedBy(3))).announced)
        assertEquals(listOf(title.id to 3), device.run(week(2)).announced)

        // Left out with episode 3 announced: the announcement is withdrawn, and nothing is announced or planned.
        device.entries = emptyList()
        assertEquals(setOf(title.id), device.run(week(2).plus(hour)).withdrawn)
        assertTrue(device.walk(week(2).plus(hour), week(3).plus(hour)).all { it.silent && it.withdrawn.isEmpty() })
        assertEquals(TitleRecord(followedFrom, announced = 3), device.record.titles[title.id])

        // Back: episode 3 is not announced again, and episode 4, which came due meanwhile, is not lost.
        device.entries = listOf(entry())
        val back = device.run(week(3).plus(hour.multipliedBy(2)))
        assertEquals(listOf(Due(Announcement(title, 4, toWatch = 4, marks = 1), week(3), week(3))), back.due)
        assertEquals(TitleRecord(followedFrom, announced = 4, standing = true), device.record.titles[title.id])
        device.walk(week(3).plus(hour.multipliedBy(2)), week(5))
        assertEquals(listOf(3, 4, 5, 6), device.episodes())
    }

    @Test
    fun `a title left out comes back as a new one once the settings stopped covering it or its status changed`() {
        val off = followed(entry())
        off.entries = emptyList()
        off.run(week(0))
        off.settings = NotificationSettings(on = false)
        off.run(week(1))
        assertEquals(NotificationRecord(), off.record)
        off.settings = NotificationSettings()
        off.entries = listOf(entry())
        assertNothingFromBefore(off)

        val changed = followed(entry(status = ListStatus.Planned), settings = NotificationSettings(alsoPlanned = true))
        changed.entries = emptyList()
        changed.run(week(0))
        changed.entries = listOf(entry())
        assertNothingFromBefore(changed)
    }

    @Test
    fun `the record holds the titles notifications are for and those with an announcement, and seeing the same again changes nothing`() {
        val set = Fixtures.reference
        val settings = NotificationSettings()
        val record = NotificationRecord().following(set.entries, settings, set.now)
        assertEquals(
            set.entries
                .filter { it.status == ListStatus.Watching }
                .map { it.anime.id }
                .toSet(),
            record.titles.keys,
        )
        assertEquals(setOf(TitleRecord(Followed(ListStatus.Watching, set.now), announced = null)), record.titles.values.toSet())
        assertEquals(record, record.following(set.entries, settings, set.now.plus(day)))
        assertEquals(record, notificationsAt(set.entries, emptyMap(), settings, record, set.now.plus(day)).record)

        // A title notifications are no longer for keeps what was announced of it, and nothing else.
        val device = followed(entry(), entry(other))
        device.run(week(0))
        device.status(ListStatus.Dropped)
        device.status(ListStatus.Dropped, other.id)
        device.run(week(0).plus(hour))
        assertEquals(mapOf(title.id to TitleRecord(followed = null, announced = 1)), device.record.titles)
    }
}

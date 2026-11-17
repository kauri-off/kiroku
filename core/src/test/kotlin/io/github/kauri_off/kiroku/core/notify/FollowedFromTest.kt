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
import java.time.Instant

// N5. At [now] three episodes have aired, the third an hour ago; the fourth airs at week(3).
class FollowedFromTest {
    private val now = week(2).plus(hour)
    private val firstRun = week(0).minus(day)
    private val none = emptyList<Pair<AnimeId, Int>>()

    // The title is followed from [now]: its first announcement is of the first episode whose notification time comes after.
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
        // S9: signed out, the record is forgotten, and it stays empty while there is no list.
        val device = followed(entry(watched = 1))
        device.run(week(1))
        device.record = NotificationRecord()
        device.announced.clear()
        device.entries = emptyList()
        device.run(week(1).plus(day))
        assertEquals(NotificationRecord(), device.record)
        // The sign-in's first download: a title changed a week or an hour ago is from before it all the same.
        device.entries = listOf(entry(updatedAt = now.minus(hour.multipliedBy(2))), entry(other, updatedAt = week(1)))
        assertNothingFromBefore(device)
        assertEquals(now, device.record.since)
        assertEquals(Followed(ListStatus.Watching, now), device.record.titles[other.id]?.followed)
    }

    @Test
    fun `N5 the first run of a build with notifications over a list an earlier build stored announces nothing from before`() =
        assertNothingFromBefore(Device(listOf(entry(watched = 1, updatedAt = week(2).minus(hour)))))

    @Test
    fun `N5 turning notifications on announces nothing from before`() {
        val never = Device(listOf(entry(updatedAt = week(2).minus(hour))), settings = NotificationSettings(on = false))
        assertEquals(none, never.walk(week(-1), week(2)).announced)
        assertEquals(NotificationRecord(), never.record)
        never.settings = NotificationSettings()
        assertNothingFromBefore(never)

        // Turned off and on again: what came due meanwhile is from before.
        val again = followed(entry())
        again.walk(week(0), week(1))
        again.settings = NotificationSettings(on = false)
        again.run(week(2).minus(hour))
        assertNull(again.record.since)
        again.settings = NotificationSettings()
        assertEquals(none, again.run(now).announced)
        assertEquals(now, again.record.since)
        assertEquals(listOf(1, 2, 4), again.also { it.walk(now, week(3)) }.episodes())
    }

    @Test
    fun `N5 turning on Also for planned titles announces nothing from before`() {
        val device = followed(entry(status = ListStatus.Planned, updatedAt = week(2).minus(hour)), entry(other))
        device.settings = NotificationSettings(alsoPlanned = true)
        // The watched title's moment is not this one: its overdue episode is announced.
        assertEquals(listOf(other.id to 2), device.run(now).announced)
        assertEquals(Followed(ListStatus.Watching, firstRun), device.record.titles[other.id]?.followed)
        assertEquals(firstRun to now, device.record.since to device.record.plannedSince)
        assertNothingFromBefore(device)
        device.settings = NotificationSettings()
        assertNull(device.run(week(3).plus(hour)).record.plannedSince)
    }

    @Test
    fun `N5 adding a title announces nothing from before`() {
        val device = followed(entry(other))
        assertNull(device.record.titles[title.id])
        device.entries += entry(updatedAt = now)
        assertNothingFromBefore(device)
    }

    @Test
    fun `N5 changing a title's status announces nothing from before`() {
        val others = listOf(ListStatus.OnHold, ListStatus.Dropped, ListStatus.Completed, ListStatus.Planned, Fixtures.unknownListStatus)
        for (from in others) {
            val device = followed(entry(status = from))
            device.status(ListStatus.Watching, at = now)
            assertNothingFromBefore(device, "$from")
        }
        // Also between the two statuses notifications are for.
        for ((from, to) in listOf(ListStatus.Planned to ListStatus.Watching, ListStatus.Watching to ListStatus.Planned)) {
            val device = followed(entry(status = from), settings = NotificationSettings(alsoPlanned = true))
            device.status(to, at = now)
            assertNothingFromBefore(device, "$from to $to")
        }
        // No change of status, no new moment, whatever else of the entry changed: the overdue episode is announced.
        val edited = followed(entry())
        edited.entries = listOf(entry(watched = 1, updatedAt = now))
        assertEquals(listOf(title.id to 3), edited.run(now).announced)
        assertEquals(Followed(ListStatus.Watching, firstRun), edited.record.titles[title.id]?.followed)
    }

    @Test
    fun `N5 a change that a sync brings is followed from when it was made on the website, and moves no other title's moment`() {
        val synced = week(2).plus(hour.multipliedBy(3))
        val changes: Map<String, (Device, Instant) -> Unit> =
            mapOf(
                "added" to { device, at -> device.entries += entry(updatedAt = at) },
                "status changed" to { device, at ->
                    device.entries += entry(status = ListStatus.OnHold)
                    device.run(firstRun)
                    device.status(ListStatus.Watching, at)
                },
            )
        for ((name, change) in changes) {
            // Made two hours before episode 3 aired and synced three hours after: the episode is announced by the sync's run.
            val before = followed(entry(other))
            change(before, week(2).minus(hour.multipliedBy(2)))
            val result = before.run(synced)
            assertEquals(
                name,
                listOf(Due(Announcement(other, 2, 2, 1), week(1).plus(NotifyCases.otherAfter), week(1).plus(NotifyCases.otherAfter))),
                result.due.take(1),
            )
            assertEquals(name, listOf(Due(Announcement(title, 3, toWatch = 3, marks = 1), week(2), week(2))), result.due.drop(1))
            assertEquals(name, Followed(ListStatus.Watching, week(2).minus(hour.multipliedBy(2))), before.record.titles[title.id]?.followed)
            assertEquals(name, Followed(ListStatus.Watching, firstRun), before.record.titles[other.id]?.followed)

            // Made an hour after episode 3 aired: the episode is from before the change.
            val after = followed(entry(other))
            change(after, now)
            assertEquals(name, listOf(other.id to 2), after.run(synced).announced)
            assertEquals(name, Followed(ListStatus.Watching, now), after.record.titles[title.id]?.followed)
            assertEquals(name, listOf(4), after.also { it.walk(synced, week(3)) }.episodes())

            // MAL's time of the change is ahead of the device's: the moment is the run's own.
            val ahead = followed(entry(other))
            change(ahead, synced.plus(hour))
            ahead.run(synced)
            assertEquals(name, Followed(ListStatus.Watching, synced), ahead.record.titles[title.id]?.followed)
        }
    }

    @Test
    fun `N5 a change made on the website before notifications covered the list is followed from when they did`() {
        // Changed two hours before episode 3 aired; notifications are turned on an hour after it.
        val off = Device(listOf(entry(updatedAt = week(2).minus(hour.multipliedBy(2)))), settings = NotificationSettings(on = false))
        off.run(week(2))
        off.settings = NotificationSettings()
        assertNothingFromBefore(off)

        // The same of a planned title and "Also for planned titles", while the list itself was covered long before.
        val planned = followed(entry(other))
        planned.entries += entry(status = ListStatus.Planned, updatedAt = week(2).minus(hour.multipliedBy(2)))
        planned.run(week(2))
        planned.settings = NotificationSettings(alsoPlanned = true)
        planned.run(now)
        assertEquals(Followed(ListStatus.Planned, now), planned.record.titles[title.id]?.followed)
        // Moved to Watching on the website before that: the list was covered then, so the change's own time counts.
        planned.status(ListStatus.Watching, at = week(2).minus(hour))
        assertEquals(listOf(title.id to 3), planned.run(now.plus(hour)).announced.filter { it.first == title.id })
    }

    @Test
    fun `N5 with a delay, an episode that aired before the change and is due after it is announced`() {
        val settings = NotificationSettings(delay = NotificationDelay.ThreeHours)
        val notifyAt = week(2).plus(hour.multipliedBy(3))
        val device = followed(entry(status = ListStatus.OnHold), settings = settings)
        device.status(ListStatus.Watching, at = now)
        val changed = device.run(now)
        assertEquals(none, changed.announced)
        assertEquals(listOf(Coming(title, 3, week(2), notifyAt)), changed.coming)
        assertEquals(listOf(Due(Announcement(title, 3, toWatch = 3, marks = 1), week(2), notifyAt)), device.run(notifyAt).due)

        // A notification time that is the moment itself is not after it.
        val atOnce = followed(entry(status = ListStatus.OnHold), settings = settings)
        atOnce.status(ListStatus.Watching, at = notifyAt)
        assertEquals(none, atOnce.run(notifyAt).announced)
        assertEquals(listOf(4), atOnce.also { it.walk(notifyAt, week(3).plus(hour.multipliedBy(3))) }.episodes())
    }

    @Test
    fun `N5 a title removed and added again is a new title, followed from the add`() {
        // Watched to 3 and announced to 4; removed; added again from 0 a day after episode 5 aired.
        val added = week(4).plus(day)
        val device = followed(entry(watched = 3))
        device.walk(week(0), week(3))
        device.remove()
        assertEquals(setOf(title.id), device.run(week(3).plus(hour)).withdrawn)
        assertEquals(TitleRecord(followed = null, announced = 4), device.record.titles[title.id])
        assertTrue(device.walk(week(3).plus(hour), added).all { it.silent && it.withdrawn.isEmpty() })
        device.entries = listOf(entry(updatedAt = added))
        val back = device.run(added)
        assertEquals(none, back.announced)
        assertEquals(emptyList<Announcement>(), back.standing)
        assertEquals(listOf(Coming(title, 6, week(5), week(5))), back.coming)
        assertEquals(TitleRecord(Followed(ListStatus.Watching, added), announced = 4), device.record.titles[title.id])
        assertEquals(listOf(4, 6), device.also { it.walk(added, week(5)) }.episodes())

        // Added again on the website and synced later: what aired after the add is announced, and nothing from before it.
        val synced = followed(entry(watched = 3))
        synced.walk(week(0), week(3))
        synced.remove()
        synced.run(week(3).plus(hour))
        synced.entries = listOf(entry(updatedAt = added))
        assertEquals(none, synced.run(week(5).minus(hour)).announced)
        synced.remove()
        synced.run(week(5).minus(hour))
        synced.entries = listOf(entry(updatedAt = week(5).minus(hour)))
        assertEquals(listOf(Due(Announcement(title, 6, toWatch = 6, marks = 1), week(5), week(5))), synced.run(week(5).plus(hour)).due)
    }

    @Test
    fun `a removal that failed leaves the title followed from where it was, and announces nothing twice`() {
        val device = followed(entry(updatedAt = week(-3)))
        assertEquals(listOf(title.id to 3), device.run(week(2)).announced)
        // W2: the entry is gone while its removal is sent, and is back as MAL last confirmed it when that fails.
        val confirmed = device.entries
        device.remove()
        assertEquals(setOf(title.id), device.run(now).withdrawn)
        device.entries = confirmed
        val back = device.run(now.plusSeconds(5))
        assertEquals(none, back.announced)
        assertEquals(emptyList<Announcement>(), back.standing)
        assertEquals(TitleRecord(Followed(ListStatus.Watching, firstRun), announced = 3), device.record.titles[title.id])
        assertEquals(listOf(3, 4), device.also { it.walk(now, week(3)) }.episodes())
    }

    @Test
    fun `a moment set while the clock was ahead is no later than the clock once it is corrected`() {
        // The clock reads five days ahead when the status changes, so the entry's own time is ahead too.
        val ahead = week(1).plus(day.multipliedBy(5))
        val device = followed(entry(status = ListStatus.OnHold))
        device.status(ListStatus.Watching, at = ahead)
        device.run(ahead)
        assertEquals(Followed(ListStatus.Watching, ahead), device.record.titles[title.id]?.followed)
        val corrected = week(1).plus(hour)
        device.run(corrected)
        assertEquals(Followed(ListStatus.Watching, corrected), device.record.titles[title.id]?.followed)
        assertEquals(listOf(title.id to 3), device.run(week(2)).announced)

        // The same of the moment notifications came to cover the list.
        val first = Device(listOf(entry()), settings = NotificationSettings(alsoPlanned = true))
        first.run(ahead)
        first.run(corrected)
        assertEquals(corrected to corrected, first.record.since to first.record.plannedSince)
        assertEquals(listOf(title.id to 3), first.run(week(2)).announced)
    }

    @Test
    fun `the record holds the titles notifications are for and those with an announcement, and seeing the same again changes nothing`() {
        val set = Fixtures.reference
        val settings = NotificationSettings()
        assertEquals(NotificationRecord(), NotificationRecord().following(emptyList(), settings, set.now))
        val record = NotificationRecord().following(set.entries, settings, set.now)
        assertEquals(set.now, record.since)
        assertNull(record.plannedSince)
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
        device.status(ListStatus.Dropped, at = week(0))
        device.status(ListStatus.Dropped, at = week(0), id = other.id)
        device.run(week(0).plus(hour))
        assertEquals(mapOf(title.id to TitleRecord(followed = null, announced = 1)), device.record.titles)
    }
}

package io.github.kauri_off.kiroku.notifysettings

import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.notify.Recheck
import java.time.Duration
import java.time.Instant
import kotlin.time.toJavaDuration

// The notification settings' states over the fixture set, for previews and screenshots.
object NotifySettingsFixtureStates {
    val set = Fixtures.reference

    private val now: Instant = set.now

    // How long after its time each notification was posted, in turn.
    private val lateness = listOf(0L, 3L, 1L, 14L, 0L, 2L)

    // How each re-check ended, in turn.
    private val rechecks = listOf(Recheck.Answered, Recheck.Failed, Recheck.Late, Recheck.Offline, Recheck.NotAsked)

    val settings = NotificationSettings()

    val allowed = DeviceState(NotificationAccess.Allowed, ExactAlarms.Allowed, BatteryUse.Unrestricted)

    // The watching titles' next episodes, soonest first.
    val upcoming: List<UpcomingNotification> =
        set.entries
            .filter { it.status == ListStatus.Watching }
            .mapNotNull { entry ->
                set.airing[entry.anime.id]?.nextEpisode?.let {
                    UpcomingNotification(entry.anime.id, entry.anime.titles.romaji, it.number, it.airsAt)
                }
            }.sortedBy { it.notifyAt }

    // The episode before each upcoming one, a week earlier, where that has passed; newest first.
    val sent: List<SentNotification> =
        upcoming
            .filter { it.episode > 1 }
            .map { it.copy(episode = it.episode - 1, notifyAt = it.notifyAt.minus(Duration.ofDays(WEEK_DAYS))) }
            .filter { it.notifyAt.isBefore(now) }
            .mapIndexed { index, it ->
                SentNotification(
                    it.id,
                    it.title,
                    it.episode,
                    airedAt = it.notifyAt,
                    sentAt = it.notifyAt.plus(Duration.ofMinutes(lateness[index % lateness.size])),
                    recheck = rechecks[index % rechecks.size],
                )
            }.sortedByDescending { it.sentAt }

    // Seven days of announcements, several a day, newest first.
    val longRecord: List<SentNotification> =
        set.anime.take(LONG_RECORD).mapIndexed { index, anime ->
            val aired = now.minus(Duration.ofMinutes(RECORD_STEP_MINUTES * index + RECORD_FIRST_MINUTES))
            SentNotification(
                anime.id,
                anime.titles.romaji,
                episode = index % EPISODES + 1,
                airedAt = aired,
                sentAt = aired.plus(Duration.ofMinutes(lateness[index % lateness.size])),
                recheck = rechecks[index % rechecks.size],
            )
        }

    val record =
        NotifyRecord(
            listSyncedAt = now.minus(Duration.ofMinutes(SYNCED_MINUTES_AGO)),
            upcoming = upcoming,
            sent = sent,
        )

    // The record with every upcoming notification [delay] after its episode airs.
    fun delayed(delay: NotificationDelay) =
        record.copy(upcoming = upcoming.map { it.copy(notifyAt = it.notifyAt.plus(delay.duration.toJavaDuration())) })

    fun state(
        settings: NotificationSettings = this.settings,
        device: DeviceState = allowed,
        record: NotifyRecord? = this.record,
    ) = NotifySettingsState(
        now = now.atZone(FixtureTime.zone),
        content = NotifySettingsContent.Shown(settings, device, record),
    )

    fun loading() = NotifySettingsState(now.atZone(FixtureTime.zone), NotifySettingsContent.Loading)

    private const val WEEK_DAYS = 7L
    private const val SYNCED_MINUTES_AGO = 95L
    private const val LONG_RECORD = 30
    private const val RECORD_STEP_MINUTES = 330L
    private const val RECORD_FIRST_MINUTES = 40L
    private const val EPISODES = 12
}

package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

// What starts a run. Every one does the same, but for the alarm's question to the airing source (N3).
enum class RunTrigger {
    Alarm,
    DeviceRestarted,
    AppUpdated,
    AlarmAccessChanged,
    AppOpened,
    Periodic,

    // The notification's action wrote to the list.
    MarkedWatched,

    // The list, the airing data, a setting or the session changed.
    Changed,
}

// N3: how a run's question to the airing source about its due titles ended.
enum class Recheck {
    // The run was not the alarm's, or nothing due could be asked about.
    NotAsked,
    Offline,
    Answered,
    Failed,
    Late,
}

// An announcement that was posted.
data class SentNotification(
    val anime: Anime,
    val episode: Int,
    val airedAt: Instant,
    val notifyAt: Instant,
    // When its run recorded it, which is after the post itself where an earlier run died between the two.
    val postedAt: Instant,
    val recheck: Recheck,
    // False when the device showed none of the app's notifications then; the episode counts as announced all the same.
    val shown: Boolean = true,
)

// How long what was posted is kept for the screens.
val sentKeptFor: Duration = 30.days

enum class RunEnd {
    Done,

    // Nobody is signed in: nothing was announced, and what was shown, planned and remembered is gone (S9).
    SignedOut,

    // The list or the store could not be read or written, or a port threw; what it did before that is in the report.
    Failed,
}

// What one run did, by the device's clock.
data class RunReport(
    val trigger: RunTrigger,
    val began: Instant,
    val ended: Instant,
    val end: RunEnd,
    val recheck: Recheck = Recheck.NotAsked,
    val posted: List<SentNotification> = emptyList(),
    // The titles whose notification the run removed.
    val withdrawn: Set<AnimeId> = emptySet(),
    // The alarm it left; none also when it failed before setting one.
    val alarm: Instant? = null,
    // The alarm could not be set, which fails the run and keeps nothing back.
    val alarmFailed: Boolean = false,
)

// Where the app starts a run: at the alarm, at the moments the system starts the app, at its opening and after the action's write.
interface NotificationRuns {
    // Returns when the run has ended. A run goes on to its end when its caller is cancelled, and runs never overlap (N1).
    suspend fun run(trigger: RunTrigger): RunReport
}

package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.notified
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// How long after an episode airs its notification time comes (section 5.3).
enum class NotificationDelay(
    val duration: Duration,
) {
    None(Duration.ZERO),
    HalfHour(30.minutes),
    Hour(1.hours),
    ThreeHours(3.hours),
}

data class NotificationSettings(
    val on: Boolean = true,
    val alsoPlanned: Boolean = false,
    val delay: NotificationDelay = NotificationDelay.None,
) {
    // Whether a title with this list status is one notifications are for.
    fun notifies(status: EntryStatus): Boolean = on && status.notified(alsoPlanned)
}

// N2: an episode is announced no later than this after its notification time.
val announcedNoLaterThan: Duration = 3.days

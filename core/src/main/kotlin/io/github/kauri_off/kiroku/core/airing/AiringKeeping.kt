package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.tracked
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.announcedNoLaterThan
import kotlin.time.Duration

// Section 4: the titles airing data is kept for, the unfinished ones of the list in Watching, Planned or On hold.
val ListEntry.tracked: Boolean get() = status.tracked && anime.airingStatus.effective != AiringStatus.Finished

// How long after it airs an episode can still be announced: N2's limit after the longest delay.
val announceableFor: Duration = NotificationDelay.entries.maxOf { it.duration } + announcedNoLaterThan

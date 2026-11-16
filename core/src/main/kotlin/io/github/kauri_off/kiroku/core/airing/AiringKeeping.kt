package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.tracked
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

// Section 4: the titles airing data is kept for, the unfinished ones of the list in Watching, Planned or On hold.
val ListEntry.tracked: Boolean get() = status.tracked && anime.airingStatus.effective != AiringStatus.Finished

// How long after it airs an episode can still be announced: N2's three days after the longest delay (section 5.3).
val announceableFor: Duration = 3.days + 3.hours

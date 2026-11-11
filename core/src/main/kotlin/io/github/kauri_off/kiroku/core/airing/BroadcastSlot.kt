package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.Broadcast
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

val JAPAN: ZoneId = ZoneId.of("Asia/Tokyo")

// The next start of MAL's weekly slot at or after [now]; null when MAL gives no time.
fun Broadcast.nextStart(now: Instant): Instant? {
    val time = japanTime ?: return null
    val japanNow = now.atZone(JAPAN)
    val candidate = japanNow.with(TemporalAdjusters.nextOrSame(dayOfWeek)).with(time)
    return (if (candidate.isBefore(japanNow)) candidate.plusWeeks(1) else candidate).toInstant()
}

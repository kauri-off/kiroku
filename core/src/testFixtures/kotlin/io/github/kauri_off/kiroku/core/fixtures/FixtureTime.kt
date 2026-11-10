package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object FixtureTime {
    val japan: ZoneId = ZoneId.of("Asia/Tokyo")
    val zone: ZoneId = ZoneId.of("Europe/Moscow")
    val now: Instant = moscow("2026-01-14T12:00")
    val clock: Clock = Clock.fixed(now, zone)

    fun moscow(localDateTime: String): Instant = LocalDateTime.parse(localDateTime).atZone(zone).toInstant()

    fun japan(localDateTime: String): Instant = LocalDateTime.parse(localDateTime).atZone(japan).toInstant()

    fun weekly(
        anchor: Int,
        anchorAt: Instant,
        episodes: IntRange,
    ): List<ScheduledEpisode> =
        episodes.map { number ->
            ScheduledEpisode(number, anchorAt.plus(Duration.ofDays(7L * (number - anchor))))
        }
}

package io.github.kauri_off.kiroku.wiring

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// A clock whose instant and zone the test sets, which also moves by [step] each time it is read.
class MovingClock(
    var instant: Instant,
    private var zoneId: ZoneId,
    private val step: Duration = Duration.ZERO,
) : Clock() {
    override fun instant(): Instant = instant.also { instant += step }

    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MovingClock(instant, zone, step)

    fun moveTo(zone: ZoneId) {
        zoneId = zone
    }
}

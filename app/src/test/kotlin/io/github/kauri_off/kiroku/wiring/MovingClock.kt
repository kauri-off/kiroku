package io.github.kauri_off.kiroku.wiring

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// A clock the test moves, which also moves by [step] each time it is read.
class MovingClock(
    var instant: Instant,
    private val zone: ZoneId,
    private val step: Duration = Duration.ZERO,
) : Clock() {
    override fun instant(): Instant = instant.also { instant += step }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MovingClock(instant, zone, step)
}

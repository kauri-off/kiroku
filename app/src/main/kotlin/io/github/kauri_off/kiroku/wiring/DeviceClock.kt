package io.github.kauri_off.kiroku.wiring

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

object DeviceClock : Clock() {
    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()

    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)
}

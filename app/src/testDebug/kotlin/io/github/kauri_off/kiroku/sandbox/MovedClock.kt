package io.github.kauri_off.kiroku.sandbox

import org.junit.rules.ExternalResource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

// The sandbox's graph on the device's time moved on by [ahead], as when that much time has passed.
class MovedClock : ExternalResource() {
    @Volatile
    var ahead: Duration = Duration.ZERO

    private val clock =
        object : Clock() {
            override fun instant(): Instant = Instant.now().plus(ahead)

            override fun getZone(): ZoneId = ZoneId.systemDefault()

            override fun withZone(zone: ZoneId): Clock = this
        }

    override fun before() {
        SandboxGraph.movedClock = clock
    }

    override fun after() {
        SandboxGraph.movedClock = null
    }
}

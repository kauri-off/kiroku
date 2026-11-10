package io.github.kauri_off.kiroku.core.fixtures

import java.time.Clock
import java.time.Duration

object FixtureSets {
    private fun clockAt(offset: Duration): Clock = Clock.fixed(FixtureTime.reference.plus(offset), FixtureTime.zone)

    val shiftClocks: Map<String, Clock> =
        mapOf(
            "43 weeks and 2 days later" to clockAt(Duration.ofDays(43 * 7 + 2).plusHours(5).plusMinutes(17)),
            "3 weeks earlier" to clockAt(Duration.ofDays(-3 * 7 + 6).plusHours(23)),
            "208 weeks later" to clockAt(Duration.ofDays(208 * 7).plusSeconds(1)),
        )

    val all: List<Pair<String, FixtureSet>> =
        listOf("reference" to Fixtures.reference) + shiftClocks.map { (name, clock) -> name to Fixtures.at(clock) }

    fun parameters(): List<Array<Any>> = all.map { (name, set) -> arrayOf(name, set) }
}

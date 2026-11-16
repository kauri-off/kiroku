package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// The tick over a calculator the test writes, on virtual time: what a calculator that fails or errs costs.
@OptIn(ExperimentalCoroutinesApi::class)
class AiringTickTest {
    private val start = FixtureTime.reference
    private val sound = FixtureAnime.exactToWatch
    private val odd = FixtureAnime.exactCaughtUp

    private class Ticked(
        scope: TestScope,
        start: Instant,
        titles: List<Anime>,
        calculate: (AiringOf, Instant) -> Airing,
    ) {
        val clock =
            object : Clock() {
                override fun getZone(): ZoneId = FixtureTime.zone

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = start.plusMillis(scope.testScheduler.currentTime)
            }
        val asked = MutableStateFlow(titles.associate { it.id to AiringOf(it, null) })
        val unexpected = mutableListOf<Throwable>()
        val calls = mutableListOf<Pair<AnimeId, Instant>>()
        val seen = mutableListOf<Map<AnimeId, Airing>>()

        // Bounded, so that a tick that does not rest fails a test rather than hangs it.
        private var waits = 0
        private val time =
            AiringTime(clock) {
                check(++waits < 50) { "The tick does not rest" }
                delay(it)
            }

        init {
            scope.backgroundScope.launch {
                asked
                    .onTick(time, { unexpected += it }, { of, now ->
                        calls += of.anime.id to now
                        calculate(of, now)
                    }) { it }
                    .collect { seen += it.second }
            }
            scope.runCurrent()
        }

        fun callsFor(anime: Anime) = calls.filter { it.first == anime.id }.map { it.second }
    }

    // A count that rises every hour on the hour from [start].
    private fun hourly(now: Instant): Airing {
        val hours =
            java.time.Duration
                .between(start, now)
                .toHours()
        return Airing(
            AiredCount.Estimated(hours.toInt() + 1),
            null,
            null,
            start.plusSeconds(3600 * hours),
            changesAt =
                start.plusSeconds(
                    3600 * (hours + 1),
                ),
        )
    }

    @Test
    fun `a title the calculator fails on has no result and is reported once, and every other title keeps its own`() =
        runTest {
            val t = Ticked(this, start, listOf(sound, odd)) { of, now -> if (of.anime == odd) error("contradictory data") else hourly(now) }
            assertEquals(mapOf(sound.id to hourly(start)), t.seen.single())
            assertEquals(listOf("contradictory data"), t.unexpected.map { it.message })

            advanceTimeBy(1.hours)
            runCurrent()
            assertEquals(mapOf(sound.id to hourly(start.plusSeconds(3600))), t.seen.last())
            assertEquals(1, t.unexpected.size)

            t.asked.value += odd.id to AiringOf(odd, AiringSchedule(emptyList(), 12))
            runCurrent()
            assertEquals(2, t.unexpected.size)
            assertEquals(setOf(sound.id), t.seen.last().keys)
        }

    @Test
    fun `a result that holds until no later than its own instant is reported once and waited on by nothing`() =
        runTest {
            val stuck = Airing(AiredCount.Estimated(3), null, null, start, changesAt = start)
            val t =
                Ticked(this, start, listOf(sound, odd)) { of, now ->
                    if (of.anime == odd &&
                        now < start.plusSeconds(3600)
                    ) {
                        stuck
                    } else {
                        hourly(now)
                    }
                }
            assertEquals(mapOf(sound.id to hourly(start), odd.id to stuck), t.seen.single())
            assertEquals(1, t.unexpected.size)

            advanceTimeBy(59.minutes)
            runCurrent()
            assertEquals(listOf(start), t.callsFor(odd))
            assertEquals(1, t.seen.size)

            advanceTimeBy(1.minutes)
            runCurrent()
            val next = start.plusSeconds(3600)
            assertEquals(listOf(start, next), t.callsFor(odd))
            assertEquals(mapOf(sound.id to hourly(next), odd.id to hourly(next)), t.seen.last())
            assertEquals(1, t.unexpected.size)
        }
}

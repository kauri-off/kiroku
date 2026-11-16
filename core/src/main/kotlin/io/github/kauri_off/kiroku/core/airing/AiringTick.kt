package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.Anime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import java.time.Clock
import java.time.Duration.between
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

// The device's time as airing reads it.
class AiringTime(
    val clock: Clock,
    // Emits when the clock was set, which a wait does not see.
    val set: Flow<Unit> = emptyFlow(),
    // Lets that much of the clock's time pass. A clock that does not run with the dispatcher's time gets a wait that never ends.
    val wait: suspend (Duration) -> Unit = { delay(it) },
)

// A title as the calculator reads it.
internal data class AiringOf(
    val anime: Anime,
    val schedule: AiringSchedule?,
)

// A2: each value with its titles' results, computed anew only for a title that changed or whose instant passed; one wait serves all.
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T, K> Flow<T>.onTick(
    time: AiringTime,
    unexpected: (Throwable) -> Unit,
    calculate: (AiringOf, Instant) -> Airing = { of, now -> airingAt(of.anime, of.schedule, now) },
    titles: (T) -> Map<K, AiringOf>,
): Flow<Pair<T, Map<K, Airing>>> =
    flow {
        class Held(
            val of: AiringOf,
            val airing: Airing?,
            val at: Instant,
        ) {
            // Until when the result holds; none for a result that names no instant after its own, which a wait must not follow.
            val until: Instant? = airing?.changesAt?.takeIf { it.isAfter(at) }
            val sound: Boolean = airing != null && airing.changesAt == until

            fun holds(
                of: AiringOf,
                now: Instant,
            ) = sound && this.of == of && !now.isBefore(at) && until?.let(now::isBefore) != false
        }

        // A title the calculator fails on has no result and is reported once; no other title is touched by it.
        fun computed(
            of: AiringOf,
            now: Instant,
            before: Held?,
        ): Held {
            val airing =
                try {
                    calculate(of, now)
                } catch (e: Exception) {
                    if (before?.of != of || before.sound) unexpected(e)
                    null
                }
            val held = Held(of, airing, now)
            if (airing != null && !held.sound && (before?.of != of || before.sound)) {
                unexpected(IllegalStateException("A result of ${of.anime.id} at $now holds until ${airing.changesAt}, which is not later"))
            }
            return held
        }

        var held = emptyMap<K, Held>()
        emitAll(
            combine(this@onTick, time.set.onStart { emit(Unit) }) { value, _ -> value }.transformLatest { value ->
                val asked = titles(value)
                var given: Map<K, Airing>? = null
                while (true) {
                    val now = time.clock.instant()
                    held = asked.mapValues { (key, of) -> held[key]?.takeIf { it.holds(of, now) } ?: computed(of, now, held[key]) }
                    val results = held.mapNotNull { (key, it) -> it.airing?.let { airing -> key to airing } }.toMap()
                    if (results != given) emit(value to results)
                    given = results
                    // A result that is not sound is computed again at the next instant of another title, never waited on.
                    val next = held.values.mapNotNull { it.until }.minOrNull() ?: break
                    // A wait that ends before its instant, as when the clock was set back meanwhile, is made again.
                    time.wait(between(now, next).toKotlinDuration())
                }
            },
        )
    }

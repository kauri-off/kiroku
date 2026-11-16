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
import kotlin.time.Duration.Companion.milliseconds
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
    titles: (T) -> Map<K, AiringOf>,
): Flow<Pair<T, Map<K, Airing>>> =
    flow {
        class Held(
            val of: AiringOf,
            val airing: Airing,
            val at: Instant,
        ) {
            fun holds(
                of: AiringOf,
                now: Instant,
            ) = this.of == of && !now.isBefore(at) && airing.changesAt?.let(now::isBefore) != false
        }

        var held = emptyMap<K, Held>()
        emitAll(
            combine(this@onTick, time.set.onStart { emit(Unit) }) { value, _ -> value }.transformLatest { value ->
                val asked = titles(value)
                var given: Map<K, Airing>? = null
                while (true) {
                    val now = time.clock.instant()
                    held =
                        asked.mapValues { (key, of) ->
                            held[key]?.takeIf { it.holds(of, now) }
                                ?: Held(of, airingAt(of.anime, of.schedule, now), now)
                        }
                    val results = held.mapValues { it.value.airing }
                    if (results != given) emit(value to results)
                    given = results
                    val next = results.values.mapNotNull { it.changesAt }.minOrNull() ?: break
                    // A wait that ends before its instant, as when the clock was set back meanwhile, is made again.
                    time.wait(between(now, next).toKotlinDuration().coerceAtLeast(1.milliseconds))
                }
            },
        )
    }

package io.github.kauri_off.kiroku.data.network

import java.time.Clock
import java.time.Duration
import java.time.Instant

// How long background work leaves MAL alone after MAL refused a request (section 2.3).
class MalBackOff(
    private val clock: Clock,
) {
    private var wait: Duration? = null
    private var until: Instant = Instant.MIN

    // Zero when background work may call MAL.
    @Synchronized
    fun remaining(): Duration = Duration.between(clock.instant(), until).coerceAtLeast(Duration.ZERO)

    // A refusal after a wait that ran out doubles the wait; one inside a wait starts the same wait again.
    @Synchronized
    internal fun refused(retryAfter: Duration?) {
        val now = clock.instant()
        val next =
            when (val last = wait) {
                null -> First
                else -> if (now < until) last else last.multipliedBy(2).coerceAtMost(Longest)
            }
        wait = next
        until = now + maxOf(next, (retryAfter ?: Duration.ZERO).coerceAtMost(Longest))
    }

    @Synchronized
    internal fun answered() {
        wait = null
        until = Instant.MIN
    }

    companion object {
        val First: Duration = Duration.ofMinutes(15)
        val Longest: Duration = Duration.ofHours(6)
    }
}

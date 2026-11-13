package io.github.kauri_off.kiroku.data.network

import java.time.Clock
import java.time.Duration
import java.time.Instant

// How long background work leaves MAL alone after MAL refused a request (section 2.3).
class MalBackOff(
    private val clock: Clock,
) {
    private var wait: Duration? = null
    private var length: Duration = Duration.ZERO
    private var until: Instant = Instant.MIN
    private var requests = 0L
    private var refusedAt = 0L

    // Zero when background work may call MAL; never more than the wait that was set, also when the clock is set back.
    @Synchronized
    fun remaining(): Duration {
        val now = clock.instant()
        if (until > now + length) until = now + length
        return Duration.between(now, until).coerceAtLeast(Duration.ZERO)
    }

    // Taken when a request is sent, so that its answer is known to be older or newer than a refusal.
    @Synchronized
    internal fun mark(): Long = ++requests

    // A refusal after a wait that ran out doubles the wait; one inside a wait starts the same wait again.
    @Synchronized
    internal fun refused(retryAfter: Duration?) {
        val now = clock.instant()
        val next =
            when (val last = wait) {
                null -> First
                else -> if (remaining() > Duration.ZERO) last else last.multipliedBy(2).coerceAtMost(Longest)
            }
        wait = next
        length = maxOf(next, (retryAfter ?: Duration.ZERO).coerceAtMost(Longest))
        until = now + length
        refusedAt = ++requests
    }

    // An answer to a request sent before the last refusal says nothing about MAL since then.
    @Synchronized
    internal fun answered(mark: Long) {
        if (mark < refusedAt) return
        wait = null
        length = Duration.ZERO
        until = Instant.MIN
    }

    companion object {
        val First: Duration = Duration.ofMinutes(15)
        val Longest: Duration = Duration.ofHours(6)
    }
}

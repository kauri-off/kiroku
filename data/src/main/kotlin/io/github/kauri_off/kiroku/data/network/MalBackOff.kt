package io.github.kauri_off.kiroku.data.network

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

// How long background work leaves MAL alone after a refusal (section 2.3), in a new process too; used off the main thread.
internal class MalBackOff(
    private val clock: Clock,
    private val kept: RefusalFile,
) {
    private var read = false
    private var stored = false
    private var wait: Duration? = null
    private var length: Duration = Duration.ZERO
    private var until: Instant = Instant.MIN
    private var requests = 0L
    private var refusedAt = 0L

    // Zero when background work may call MAL; never more than the wait that was set, also when the clock is set back.
    @Synchronized
    fun remaining(): Duration {
        val now = clock.instant()
        load(now)
        if (until > now + length) {
            until = now + length
            keep()
        }
        return Duration.between(now, until).coerceAtLeast(Duration.ZERO)
    }

    // Taken when a request is sent, so that its answer is known to be older or newer than a refusal.
    @Synchronized
    fun mark(): Long = ++requests

    // A refusal after a wait that ran out doubles the wait; one inside a wait starts the same wait again.
    @Synchronized
    fun refused(retryAfter: Duration?) {
        val now = clock.instant()
        load(now)
        val next =
            when (val last = wait) {
                null -> First
                else -> if (remaining() > Duration.ZERO) last else last.multipliedBy(2).coerceAtMost(Longest)
            }
        wait = next
        length = maxOf(next, (retryAfter ?: Duration.ZERO).coerceAtMost(Longest))
        until = now + length
        refusedAt = ++requests
        keep()
    }

    // An answer to a request sent before the last refusal says nothing about MAL since then.
    @Synchronized
    fun answered(mark: Long) {
        if (mark < refusedAt) return
        load(clock.instant())
        wait = null
        length = Duration.ZERO
        until = Instant.MIN
        if (stored) kept.clear()
        stored = false
    }

    // What an earlier process kept holds for what was left of it when this one first asked.
    private fun load(now: Instant) {
        if (read) return
        read = true
        val held = kept.read() ?: return
        stored = true
        wait = held.wait.toJavaDuration()
        length = held.remaining.toJavaDuration()
        until = now + length
    }

    private fun keep() {
        kept.write(length.toKotlinDuration(), (wait ?: return).toKotlinDuration())
        stored = true
    }

    companion object {
        val First: Duration = Duration.ofMinutes(15)
        val Longest: Duration = Duration.ofHours(6)
    }
}

// The back-off's file could not be read or deleted.
internal class BackOffFileFault : Exception()

package io.github.kauri_off.kiroku.data.network

import java.io.File
import java.time.Clock
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

// How long background work leaves MAL alone after a refusal (section 2.3), in a new process too; used off the main thread.
internal class MalBackOff(
    private val kept: RefusalFile,
) {
    private var requests = 0L
    private var refusedAt = 0L

    // Zero when background work may call MAL.
    @Synchronized
    fun remaining(): Duration = kept.read()?.remaining?.toJavaDuration() ?: Duration.ZERO

    // Taken when a request is sent, so that its answer is known to be older or newer than a refusal.
    @Synchronized
    fun mark(): Long = ++requests

    // A refusal after a wait that ran out doubles the wait; one inside a wait starts the same wait again.
    @Synchronized
    fun refused(retryAfter: Duration?) {
        val last = kept.read()
        val next =
            when {
                last == null -> First
                last.remaining.isPositive() -> last.wait.toJavaDuration()
                else ->
                    last.wait
                        .toJavaDuration()
                        .multipliedBy(2)
                        .coerceAtMost(Longest)
            }
        kept.write(maxOf(next, (retryAfter ?: Duration.ZERO).coerceAtMost(Longest)).toKotlinDuration(), next.toKotlinDuration())
        refusedAt = ++requests
    }

    // Only a success ends the wait, and one to a request sent before the last refusal says nothing about MAL since then.
    @Synchronized
    fun answered(mark: Long) {
        if (mark >= refusedAt) kept.clear()
    }

    companion object {
        val First: Duration = Duration.ofMinutes(15)
        val Longest: Duration = Duration.ofHours(6)

        fun file(
            file: File,
            clock: Clock,
            report: (Throwable) -> Unit,
        ) = RefusalFile(file, clock, Longest.toKotlinDuration(), report) { BackOffFileFault() }
    }
}

// The back-off's file could not be read or deleted.
internal class BackOffFileFault : Exception()

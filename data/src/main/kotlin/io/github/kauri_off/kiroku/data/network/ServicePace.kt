package io.github.kauri_off.kiroku.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers
import java.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// When a client of a service other than MAL may ask next: [spacing] apart, later after a failure, not at all while refused.
internal class ServicePace(
    private val time: TimeSource.WithComparableMarks,
    // The service's refusal for a new process, which reads it at its first turn.
    private val block: RefusalFile,
    private var spacing: Duration,
    private val afterFailure: Duration,
    private val firstRefusal: Duration,
    private val longestRefusal: Duration,
    // A Retry-After is honoured in full up to this; a longer one is taken for a broken header.
    private val longestRetryAfter: Duration,
) {
    private var next: ComparableTimeMark? = null
    private var refusedUntil: ComparableTimeMark? = null
    private var lastRefusal: Duration? = null
    private var loaded = false
    private var stored = false

    // Waits for the next turn and takes it, so a request cancelled after it still holds the next back; false while refused.
    suspend fun turn(): Boolean {
        if (!loaded) {
            loaded = true
            withContext(Dispatchers.IO) { block.read() }?.let { held ->
                refusedUntil = time.markNow() + held.remaining
                lastRefusal = held.wait
                stored = true
            }
        }
        if (refusedUntil?.hasPassedNow() == false) return false
        next?.let { delay(-it.elapsedNow()) }
        next = time.markNow() + spacing
        return true
    }

    // An answer the service gave, with the spacing it asks for from now on, if it said.
    suspend fun answered(spacing: Duration? = null) {
        if (spacing != null) this.spacing = spacing
        lastRefusal = null
        next = time.markNow() + this.spacing
        if (stored) {
            withContext(Dispatchers.IO) { block.clear() }
            stored = false
        }
    }

    fun failed() {
        next = time.markNow() + maxOf(spacing, afterFailure)
    }

    // A refusal before an answer doubles the last wait; [retryAfter] lengthens the hold, and the hold outlives the process.
    suspend fun refused(retryAfter: Duration?) {
        val wait = lastRefusal?.let { (it * 2).coerceAtMost(longestRefusal) } ?: firstRefusal
        lastRefusal = wait
        val held = maxOf(wait, (retryAfter ?: Duration.ZERO).coerceIn(Duration.ZERO, longestRetryAfter))
        refusedUntil = time.markNow() + held
        next = refusedUntil
        withContext(Dispatchers.IO) { block.write(held, wait) }
        stored = true
    }
}

// A Retry-After in seconds or as a date; a date in the past is zero.
internal fun retryAfter(
    headers: Headers,
    clock: Clock,
): Duration? {
    val value = headers["Retry-After"]?.trim() ?: return null
    value.toLongOrNull()?.let { return it.seconds }
    val at = headers.getInstant("Retry-After") ?: return null
    return (at.epochSecond - clock.instant().epochSecond).coerceAtLeast(0).seconds
}

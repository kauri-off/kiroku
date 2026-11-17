package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.data.network.RefusalFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// When the client may ask AniList next: at half the rate AniList reports, later after a failure, not at all while refused.
internal class AniListPace(
    private val time: TimeSource.WithComparableMarks,
    // AniList's refusal for a new process, which reads it at its first turn.
    private val block: RefusalFile,
) {
    private var spacing = spacingFor(ASSUMED_LIMIT)
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
        if (refused()) return false
        next?.let { delay(-it.elapsedNow()) }
        next = time.markNow() + spacing
        return true
    }

    private fun refused(): Boolean = refusedUntil?.hasPassedNow() == false

    // An answer that AniList gave, with the limit it reported.
    suspend fun answered(limit: Int?) {
        if (limit != null && limit > 0) spacing = spacingFor(limit)
        lastRefusal = null
        next = time.markNow() + spacing
        if (stored) {
            withContext(Dispatchers.IO) { block.clear() }
            stored = false
        }
    }

    fun failed() {
        next = time.markNow() + maxOf(spacing, AfterFailure)
    }

    // A refusal before an answer doubles the last wait; [retryAfter] lengthens the hold, and the hold outlives the process.
    suspend fun refused(retryAfter: Duration?) {
        val wait = lastRefusal?.let { (it * 2).coerceAtMost(LongestRefusal) } ?: FirstRefusal
        lastRefusal = wait
        val held = maxOf(wait, (retryAfter ?: Duration.ZERO).coerceIn(Duration.ZERO, LongestRetryAfter))
        refusedUntil = time.markNow() + held
        next = refusedUntil
        withContext(Dispatchers.IO) { block.write(held, wait) }
        stored = true
    }

    companion object {
        // AniList's documented limit while degraded, assumed until an answer reports one, and its usual one, never exceeded.
        const val ASSUMED_LIMIT = 30
        const val HIGHEST_LIMIT = 90

        val AfterFailure = 15.seconds
        val FirstRefusal = 1.minutes
        val LongestRefusal = 1.hours

        // A Retry-After is honoured in full up to a day; a longer one is taken for a broken header.
        val LongestRetryAfter = 24.hours

        fun spacingFor(limit: Int): Duration = 2.minutes / limit.coerceAtMost(HIGHEST_LIMIT)

        fun file(
            file: File,
            clock: Clock,
            report: (Throwable) -> Unit,
        ) = RefusalFile(file, clock, maxOf(LongestRefusal, LongestRetryAfter), report) { AniListOddAnswer("block") }
    }
}

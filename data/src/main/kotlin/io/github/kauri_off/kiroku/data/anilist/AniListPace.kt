package io.github.kauri_off.kiroku.data.anilist

import kotlinx.coroutines.delay
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// When the client may ask AniList next: at half the rate AniList reports, later after a failure, not at all while refused.
internal class AniListPace(
    private val time: TimeSource.WithComparableMarks,
) {
    private var spacing = spacingFor(ASSUMED_LIMIT)
    private var next: ComparableTimeMark? = null
    private var refusedUntil: ComparableTimeMark? = null
    private var lastRefusal: Duration? = null

    // Waits for the next turn; false while AniList's refusal holds, when no request may be sent.
    suspend fun turn(): Boolean {
        if (refused()) return false
        next?.let { delay(-it.elapsedNow()) }
        return true
    }

    fun refused(): Boolean = refusedUntil?.hasPassedNow() == false

    // An answer that AniList gave, with the limit it reported.
    fun answered(limit: Int?) {
        if (limit != null && limit > 0) spacing = spacingFor(limit)
        lastRefusal = null
        next = time.markNow() + spacing
    }

    fun failed() {
        next = time.markNow() + maxOf(spacing, AfterFailure)
    }

    // A refusal right after the last one's wait doubles that wait; [retryAfter] lengthens it.
    fun refused(retryAfter: Duration?) {
        val wait = lastRefusal?.let { (it * 2).coerceAtMost(LongestRefusal) } ?: FirstRefusal
        lastRefusal = wait
        val held = maxOf(wait, (retryAfter ?: Duration.ZERO).coerceAtMost(LongestRefusal))
        refusedUntil = time.markNow() + held
        next = refusedUntil
    }

    companion object {
        // AniList's documented limit while degraded, assumed until an answer reports one, and its usual one, never exceeded.
        const val ASSUMED_LIMIT = 30
        const val HIGHEST_LIMIT = 90

        val AfterFailure = 15.seconds
        val FirstRefusal = 1.minutes
        val LongestRefusal = 1.hours

        fun spacingFor(limit: Int): Duration = 2.minutes / limit.coerceAtMost(HIGHEST_LIMIT)
    }
}

package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.data.network.RefusalFile
import io.github.kauri_off.kiroku.data.network.ServicePace
import java.io.File
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// When the client may ask AniList next: at half the rate AniList reports, later after a failure, not at all while refused.
internal class AniListPace(
    time: TimeSource.WithComparableMarks,
    // AniList's refusal for a new process, which reads it at its first turn.
    block: RefusalFile,
) {
    private val pace =
        ServicePace(time, block, spacingFor(ASSUMED_LIMIT), AfterFailure, FirstRefusal, LongestRefusal, LongestRetryAfter)

    suspend fun turn(): Boolean = pace.turn()

    // An answer that AniList gave, with the limit it reported.
    suspend fun answered(limit: Int?) = pace.answered(limit?.takeIf { it > 0 }?.let(::spacingFor))

    fun failed() = pace.failed()

    suspend fun refused(retryAfter: Duration?) = pace.refused(retryAfter)

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

package io.github.kauri_off.kiroku.data.tenrai

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.score.ScoreDistribution
import io.github.kauri_off.kiroku.core.score.ScoreDistributionSource
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.RefusalFile
import io.github.kauri_off.kiroku.data.network.Sent
import io.github.kauri_off.kiroku.data.network.ServicePace
import io.github.kauri_off.kiroku.data.network.retryAfter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// MAL's score distribution of a title from Tenrai (section 2.1), asked over the one HTTP client with the title's MAL id
// alone, one request at a time and well under Tenrai's limit.
class TenraiScores(
    private val http: MalHttp,
    private val clock: Clock,
    // Where Tenrai's refusal is kept across a process's death; one client, and one file, per process.
    blockFile: File,
    address: String = TenraiStatistics.ADDRESS,
    time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : ScoreDistributionSource {
    private val url = address.toHttpUrl()
    private val pace =
        ServicePace(
            time,
            RefusalFile(blockFile, clock, maxOf(LongestRefusal, LongestRetryAfter), http.unexpected) { TenraiOddAnswer("block") },
            Spacing,
            AfterFailure,
            FirstRefusal,
            LongestRefusal,
            LongestRetryAfter,
        )
    private val turns = Mutex()

    override suspend fun distribution(id: AnimeId): Outcome<ScoreDistribution?> =
        turns.withLock {
            if (id.value < 1) return@withLock Outcome.Failure(DataError.InvalidRequest)
            if (!pace.turn()) return@withLock Outcome.Failure(DataError.Refused)
            val sent =
                http.send(
                    Request
                        .Builder()
                        .url(TenraiStatistics.url(id.value, url))
                        .header("Accept", "application/json")
                        .build(),
                )
            if (sent is Sent.Unanswered) {
                pace.failed()
                return@withLock Outcome.Failure(if (sent.offline) DataError.NoConnection else DataError.ServerError)
            }
            sent as Sent.Answered
            when {
                sent.status == 403 || sent.status == 429 -> {
                    pace.refused(retryAfter(sent.headers, clock))
                    Outcome.Failure(DataError.Refused)
                }
                sent.status == 404 && tenraiHasNoRecord(sent.body) -> {
                    pace.answered()
                    Outcome.Success(null)
                }
                sent.status == 200 -> {
                    val read = tenraiDistribution(sent.body, http.unexpected)
                    if (read == null) pace.failed() else pace.answered()
                    read?.let { Outcome.Success(it) } ?: Outcome.Failure(DataError.ServerError)
                }
                else -> {
                    pace.failed()
                    if (sent.status == 404) http.unexpected(TenraiOddAnswer("404"))
                    val asked = sent.status in 400..499 && sent.status != 408 && sent.status != 404
                    Outcome.Failure(if (asked) DataError.InvalidRequest else DataError.ServerError)
                }
            }
        }

    companion object {
        // One request a second: half of Tenrai's 120 a minute and a quarter of its 4 a second.
        val Spacing = 1.seconds
        val AfterFailure = 5.seconds
        val FirstRefusal = 1.minutes
        val LongestRefusal = 1.hours

        // Tenrai says an anti-abuse refusal normally clears within a day; a longer Retry-After is taken for a broken one.
        val LongestRetryAfter = 24.hours
    }
}

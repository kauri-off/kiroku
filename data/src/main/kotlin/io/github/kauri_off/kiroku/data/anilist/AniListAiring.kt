package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.AiringSource
import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.Sent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// AniList's episode times of titles by MAL id (section 2.2), asked over the one HTTP client with the MAL ids alone.
class AniListAiring(
    private val http: MalHttp,
    private val clock: Clock,
    address: String = AniListSchedule.ADDRESS,
    time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : AiringSource {
    private val url = address.toHttpUrl()
    private val pace = AniListPace(time)
    private val turns = Mutex()

    // Whole schedules: every title's pages of episodes are followed to the last, so nothing is left out.
    override suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>> = turns.withLock { Fetch(titles.map { it.id }.distinct()).run() }

    private inner class Fetch(
        ids: List<AnimeId>,
    ) {
        private val results = mutableMapOf<AnimeId, Outcome<FetchedAiring?>>()
        private val asked = mutableMapOf<Int, AnimeId>()

        // Each title's entries, in AniList's order, with the pages of their schedules read so far.
        private val gathered = mutableMapOf<Int, List<AniListEntry>>()

        // Why no more requests are sent in this call.
        private var stopped: DataError? = null

        init {
            for (id in ids) {
                if (id.value in 1..Int.MAX_VALUE) asked[id.value.toInt()] = id else results[id] = Outcome.Failure(DataError.InvalidRequest)
            }
        }

        suspend fun run(): Map<AnimeId, Outcome<FetchedAiring?>> {
            firstPages(firstPages(asked.keys.toList(), last = false), last = true)
            for (schedulePage in 2..Int.MAX_VALUE) {
                val more = gathered.filterValues { entries -> entries.any { it.moreEpisodes } }.keys.toList()
                if (more.isEmpty()) break
                if (schedulePage > MOST_SCHEDULE_PAGES) {
                    failed(more, DataError.ServerError)
                    break
                }
                laterPages(more, schedulePage)
            }
            val now = clock.instant()
            for ((malId, entries) in gathered) {
                val entry = chosen(entries, now)
                val episodes = entry.episodes.sortedWith(compareBy({ it.number }, { it.airsAt }))
                results[asked.getValue(malId)] = Outcome.Success(FetchedAiring(AiringSchedule(episodes, entry.total)))
            }
            return results
        }

        // First pages of episodes; an id missing from several pages of titles may have moved, and is returned to ask again.
        private suspend fun firstPages(
            ids: List<Int>,
            last: Boolean,
        ): List<Int> {
            if (ids.isEmpty()) return ids
            val answer = asked(ids, schedulePage = 1)
            val again = mutableListOf<Int>()
            for (id in ids) {
                val entries = answer.entries[id]
                when {
                    id in answer.unreadable -> failed(listOf(id), DataError.ServerError)
                    entries != null -> gathered[id] = entries
                    answer.failure != null -> failed(listOf(id), answer.failure)
                    answer.pages == 1 -> results[asked.getValue(id)] = Outcome.Success(null)
                    last -> failed(listOf(id), DataError.ServerError)
                    else -> again += id
                }
            }
            return again
        }

        // Asks for the next page of each title's episodes; its entries must be the ones of the pages before.
        private suspend fun laterPages(
            ids: List<Int>,
            schedulePage: Int,
        ) {
            val answer = asked(ids, schedulePage)
            for (id in ids) {
                val before = gathered.getValue(id)
                val now = answer.entries[id]
                val same =
                    now != null && now.size == before.size && now.zip(before).all { (n, b) -> n.total == b.total && n.status == b.status }
                when {
                    id in answer.unreadable || (now == null && answer.failure == null) || (now != null && !same) ->
                        failed(listOf(id), DataError.ServerError)
                    now == null -> failed(listOf(id), answer.failure!!)
                    else ->
                        gathered[id] =
                            before.zip(now) { b, n -> b.copy(episodes = b.episodes + n.episodes, moreEpisodes = n.moreEpisodes) }
                }
            }
        }

        // Every page of titles of one request: after a failed page the next is asked while ids remain, and the failure kept.
        private suspend fun asked(
            ids: List<Int>,
            schedulePage: Int,
        ): Asked {
            val entries = mutableListOf<AniListEntry>()
            val unreadable = mutableSetOf<Int>()
            val asked = ids.toSet()
            var failure: DataError? = null
            var page = 0
            while (true) {
                if (++page > ids.size / AniListSchedule.TITLES_PER_PAGE + MOST_EXTRA_PAGES) {
                    failure = failure ?: DataError.ServerError
                    break
                }
                val read = request(ids, page, schedulePage)
                if (read is Read.Failed) {
                    failure = failure ?: read.error
                    if (stopped != null || page * AniListSchedule.TITLES_PER_PAGE >= ids.size) break
                    continue
                }
                read as Read.Page
                entries += read.page.entries.filter { it.malId in asked }
                unreadable += read.page.unreadable
                if (!read.page.moreTitles) break
            }
            return Asked(entries.groupBy { it.malId }, unreadable, page, failure)
        }

        private suspend fun request(
            ids: List<Int>,
            page: Int,
            schedulePage: Int,
        ): Read {
            stopped?.let { return Read.Failed(it) }
            if (!pace.turn()) return stop(DataError.Refused)
            val body = AniListSchedule.body(ids, page, schedulePage).toRequestBody(JsonType)
            val sent =
                http.send(
                    Request
                        .Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .post(body)
                        .build(),
                )
            if (sent is Sent.Unanswered) {
                pace.failed()
                return if (sent.offline) stop(DataError.NoConnection) else Read.Failed(DataError.ServerError)
            }
            sent as Sent.Answered
            if (sent.status == 403 || sent.status == 429) {
                pace.refused(retryAfter(sent.headers))
                return stop(DataError.Refused)
            }
            val read = if (sent.status == 200) aniListPage(sent.body, http.unexpected) else null
            if (read == null) {
                pace.failed()
                return Read.Failed(if (sent.status in 400..499) DataError.InvalidRequest else DataError.ServerError)
            }
            pace.answered(sent.headers[LIMIT]?.trim()?.toIntOrNull())
            return Read.Page(read)
        }

        private fun stop(error: DataError): Read {
            stopped = error
            return Read.Failed(error)
        }

        private fun failed(
            ids: List<Int>,
            error: DataError,
        ) {
            for (id in ids) {
                gathered.remove(id)
                results[asked.getValue(id)] = Outcome.Failure(error)
            }
        }
    }

    private fun retryAfter(headers: Headers): Duration? {
        val value = headers[RETRY_AFTER]?.trim() ?: return null
        value.toLongOrNull()?.let { return it.seconds }
        val at = headers.getInstant(RETRY_AFTER) ?: return null
        return (at.epochSecond - clock.instant().epochSecond).seconds
    }

    private class Asked(
        val entries: Map<Int, List<AniListEntry>>,
        val unreadable: Set<Int>,
        val pages: Int,
        val failure: DataError?,
    )

    private sealed interface Read {
        class Page(
            val page: AniListPage,
        ) : Read

        class Failed(
            val error: DataError,
        ) : Read
    }

    private companion object {
        val JsonType = "application/json".toMediaType()
        const val LIMIT = "X-RateLimit-Limit"
        const val RETRY_AFTER = "Retry-After"

        // More pages than these fail the titles concerned: a thousand episodes, or more pages of titles than their ids fill.
        const val MOST_SCHEDULE_PAGES = 40
        const val MOST_EXTRA_PAGES = 4
    }
}

// Of entries with one MAL id: the latest aired episode's at [now], else the soonest first episode's, else AniList's first.
internal fun chosen(
    entries: List<AniListEntry>,
    now: Instant,
): AniListEntry {
    val aired =
        entries.mapNotNull { entry ->
            entry.episodes
                .filter { it.airsAt <= now }
                .maxOfOrNull { it.airsAt }
                ?.let { entry to it }
        }
    if (aired.isNotEmpty()) return aired.maxBy { it.second }.first
    val coming = entries.mapNotNull { entry -> entry.episodes.minOfOrNull { it.airsAt }?.let { entry to it } }
    if (coming.isNotEmpty()) return coming.minBy { it.second }.first
    return entries.first()
}

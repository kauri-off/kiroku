package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.AiringSource
import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
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
import java.io.File
import java.time.Clock
import java.time.Instant
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// AniList's episode times of titles by MAL id (section 2.2), asked over the one HTTP client with the MAL ids alone.
class AniListAiring(
    private val http: MalHttp,
    private val clock: Clock,
    // Where AniList's refusal is kept across a process's death; one client, and one file, per process.
    blockFile: File,
    address: String = AniListSchedule.ADDRESS,
    private val time: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : AiringSource {
    private val url = address.toHttpUrl()
    private val pace = AniListPace(time, clock, AniListBlock(blockFile, clock, http.unexpected))
    private val turns = Mutex()

    // Each title whose fetch failed, by MAL id: when, and how; within the cool-down it is answered so without asking.
    private val failures = mutableMapOf<Int, Pair<ComparableTimeMark, DataError>>()

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
                val malId = id.value.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
                val failure = malId?.let(failures::get)?.takeIf { (at, _) -> (at + FailureCoolDown).hasNotPassedNow() }
                when {
                    malId == null -> results[id] = Outcome.Failure(DataError.InvalidRequest)
                    failure != null -> results[id] = Outcome.Failure(failure.second)
                    else -> asked[malId] = id
                }
            }
        }

        suspend fun run(): Map<AnimeId, Outcome<FetchedAiring?>> {
            fetched()
            for ((malId, id) in asked) {
                val result = results.getValue(id)
                val error = (result as? Outcome.Failure)?.error
                if (error == null || error == DataError.NoConnection || error == DataError.Refused) {
                    failures.remove(malId)
                } else {
                    failures[malId] = time.markNow() to error
                }
            }
            return results
        }

        private suspend fun fetched() {
            firstPages(asked.keys.toList(), last = false).chunked(AniListSchedule.TITLES_PER_PAGE).forEach { firstPages(it, last = true) }
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
                val schedule = AiringSchedule(episodes, entry.total, doubtedOver(entry, entries, now))
                results[asked.getValue(malId)] = Outcome.Success(FetchedAiring(schedule))
            }
        }

        // First pages of episodes; only one page of titles settles an id, and after a failed page more ids than fill one are asked again.
        private suspend fun firstPages(
            ids: List<Int>,
            last: Boolean,
        ): List<Int> {
            if (ids.isEmpty()) return ids
            val answer = asked(ids, schedulePage = 1)
            val settled = answer.pages == 1 && answer.failure == null
            val again = mutableListOf<Int>()
            for (id in ids) {
                val entries = answer.entries[id]
                val stop = stopped
                when {
                    id in answer.unreadable -> failed(listOf(id), DataError.ServerError)
                    settled && entries == null -> results[asked.getValue(id)] = Outcome.Success(null)
                    answer.failure == null && entries != null -> gathered[id] = entries
                    stop != null -> failed(listOf(id), stop)
                    last || (answer.failure != null && ids.size <= AniListSchedule.TITLES_PER_PAGE) ->
                        failed(listOf(id), answer.failure ?: DataError.ServerError)
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
                val shifted = same && now.zip(before).any { (n, b) -> !continues(b, n) }
                if (shifted) http.unexpected(AniListOddAnswer("airingSchedule"))
                when {
                    id in answer.unreadable || (now == null && answer.failure == null) || (now != null && !same) || shifted ->
                        failed(listOf(id), DataError.ServerError)
                    now == null -> failed(listOf(id), answer.failure!!)
                    else ->
                        gathered[id] =
                            before.zip(now) { b, n -> b.copy(episodes = b.episodes + n.episodes, moreEpisodes = n.moreEpisodes) }
                }
            }
        }

        // Every page of titles of one request, the first failure kept; past a later round's failed page the next is still asked.
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
                    if (stopped != null || schedulePage == 1 || page * AniListSchedule.TITLES_PER_PAGE >= ids.size) break
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
                return Read.Failed(if (sent.status in 400..499 && sent.status != 408) DataError.InvalidRequest else DataError.ServerError)
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
        return (at.epochSecond - clock.instant().epochSecond).coerceAtLeast(0).seconds
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

// How long a title whose fetch failed is answered with that failure without a request; offline and refused excepted.
internal val FailureCoolDown: Duration = 15.minutes

// A page of a schedule goes on from the one before: not empty where more were promised, and from the next number.
private fun continues(
    before: AniListEntry,
    page: AniListEntry,
): Boolean {
    val first = page.episodes.firstOrNull() ?: return !before.moreEpisodes
    val last = before.episodes.lastOrNull() ?: return true
    return first.number == last.number + 1
}

// Of entries with one MAL id: releasing, then highest aired number at [now], latest aired, soonest first episode, first.
internal fun chosen(
    entries: List<AniListEntry>,
    now: Instant,
): AniListEntry {
    fun AniListEntry.aired() = episodes.filter { it.airsAt <= now }
    val preference =
        compareBy<AniListEntry> { it.status == RELEASING }
            .thenBy { entry -> entry.aired().maxOfOrNull { it.number } ?: 0 }
            .thenBy { entry -> entry.aired().maxOfOrNull { it.airsAt } ?: Instant.MIN }
            .thenByDescending { entry -> entry.episodes.minOfOrNull { it.airsAt } ?: Instant.MAX }
    return entries.reduce { best, entry -> if (preference.compare(entry, best) > 0) entry else best }
}

// Another entry's highest aired number when either restarts below the other or [chosen] aired less; a one-episode entry is a special.
internal fun doubtedOver(
    chosen: AniListEntry,
    entries: List<AniListEntry>,
    now: Instant,
): Int? {
    fun AniListEntry.aired() = episodes.filter { it.airsAt <= now }
    val own = chosen.aired()
    return entries
        .filter { it != chosen && it.total != 1 }
        .mapNotNull { other ->
            val theirs = other.aired()
            // A finished entry without a schedule has aired up to its total, at times not known.
            val untimed = other.total?.takeIf { other.episodes.isEmpty() && other.status == FINISHED }
            if (untimed != null) return@mapNotNull untimed.takeIf { (chosen.episodes.minOfOrNull { it.number } ?: 0) < it }
            val highest = theirs.maxOfOrNull { it.number } ?: return@mapNotNull null
            highest.takeIf { it > (own.maxOfOrNull { it.number } ?: 0) || restarts(own, theirs) || restarts(theirs, own) }
        }.maxOrNull()
}

// [later] begins to air after [earlier]'s last aired episode, at a number below the highest [earlier] reached.
private fun restarts(
    earlier: List<ScheduledEpisode>,
    later: List<ScheduledEpisode>,
): Boolean {
    val first = later.minByOrNull { it.airsAt } ?: return false
    val last = earlier.maxOfOrNull { it.airsAt } ?: return false
    return first.airsAt > last && later.minOf { it.number } < earlier.maxOf { it.number }
}

private const val RELEASING = "RELEASING"
private const val FINISHED = "FINISHED"

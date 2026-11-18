package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.toJavaDuration

// A source of episode times that answers from the schedules it holds, found by id alone, with scripted failures.
class ScriptedAiringSource(
    schedules: Map<AnimeId, AiringSchedule>,
    private val clock: Clock,
    // How long every call takes.
    @Volatile var latency: Duration = Duration.ZERO,
) : AiringSource {
    // What the source has; a title that is not in it is one the source does not know.
    @Volatile
    var schedules: Map<AnimeId, AiringSchedule> = schedules

    // Every title fails so.
    @Volatile
    var failure: DataError? = null

    // These titles cannot be answered whole.
    @Volatile
    var failing: Map<AnimeId, DataError> = emptyMap()

    // It leaves out what aired before the instant asked for, or before the same age by its own clock when that is earlier.
    @Volatile
    var partial = false

    // Thrown by every call, as a client that fails to keep the port's word would.
    @Volatile
    var throwing: Exception? = null

    // What becomes of an answer on its way out.
    @Volatile
    var answering: (Map<AnimeId, Outcome<FetchedAiring?>>) -> Map<AnimeId, Outcome<FetchedAiring?>> = { it }

    // Of each call, these titles are handed over whole before the call's latency, as a source whose pages come one by one.
    @Volatile
    var handedFirst: Set<AnimeId> = emptySet()

    // The calls received: the ids each asked for, the titles as handed over, and the instant.
    val calls = CopyOnWriteArrayList<Set<AnimeId>>()
    val handed = CopyOnWriteArrayList<Anime>()
    val asked = CopyOnWriteArrayList<Instant>()

    private val running = AtomicInteger()
    private val most = AtomicInteger()

    // The most calls that ran at one time.
    val mostAtOnce: Int get() = most.get()

    override suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>> = fetch(titles, since) {}

    override suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
        whole: suspend (Map<AnimeId, Outcome<FetchedAiring?>>) -> Unit,
    ): Map<AnimeId, Outcome<FetchedAiring?>> {
        val ids = titles.mapTo(LinkedHashSet()) { it.id }
        calls += ids
        handed += titles
        asked += since
        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        try {
            ids.filter { it in handedFirst }.takeIf { it.isNotEmpty() }?.let { first -> whole(answering(answer(first.toSet(), since))) }
            delay(latency)
            throwing?.let { throw it }
            return answering(answer(ids, since))
        } finally {
            running.decrementAndGet()
        }
    }

    private fun answer(
        ids: Set<AnimeId>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>> {
        failure?.let { error -> return ids.associateWith { Outcome.Failure(error) } }
        val from = minOf(since, clock.instant() - announceableFor.toJavaDuration())
        return ids.associateWith { id ->
            val error = failing[id]
            val schedule = schedules[id]
            when {
                error != null -> Outcome.Failure(error)
                schedule == null -> Outcome.Success(null)
                !partial -> Outcome.Success(FetchedAiring(schedule))
                else -> Outcome.Success(FetchedAiring(schedule.copy(episodes = schedule.episodes.filter { it.airsAt >= from }), from))
            }
        }
    }
}

package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.delay
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

// A source of episode times that answers from the schedules it holds, by its own clock, with scripted failures.
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

    // The source answers only the episodes that have not aired by its clock.
    @Volatile
    var upcomingOnly = false

    // Thrown by every call, as a client that fails to keep the port's word would.
    @Volatile
    var throwing: Exception? = null

    // What becomes of an answer on its way out.
    @Volatile
    var answering: (Map<AnimeId, Outcome<FetchedAiring?>>) -> Map<AnimeId, Outcome<FetchedAiring?>> = { it }

    // The calls received, each with the titles it asked for.
    val calls = CopyOnWriteArrayList<Set<AnimeId>>()

    private val running = AtomicInteger()
    private val most = AtomicInteger()

    // The most calls that ran at one time.
    val mostAtOnce: Int get() = most.get()

    override suspend fun fetch(ids: Set<AnimeId>): Map<AnimeId, Outcome<FetchedAiring?>> {
        calls += ids
        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        try {
            delay(latency)
            throwing?.let { throw it }
            return answering(answer(ids))
        } finally {
            running.decrementAndGet()
        }
    }

    private fun answer(ids: Set<AnimeId>): Map<AnimeId, Outcome<FetchedAiring?>> {
        failure?.let { error -> return ids.associateWith { Outcome.Failure(error) } }
        val now = clock.instant()
        return ids.associateWith { id ->
            val error = failing[id]
            val schedule = schedules[id]
            when {
                error != null -> Outcome.Failure(error)
                schedule == null -> Outcome.Success(null)
                !upcomingOnly -> Outcome.Success(FetchedAiring(schedule))
                else -> Outcome.Success(FetchedAiring(schedule.copy(episodes = schedule.episodes.filter { it.airsAt >= now }), now))
            }
        }
    }
}

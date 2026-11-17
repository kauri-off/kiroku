package io.github.kauri_off.kiroku.core.score

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

// How many of MAL's members gave a title each score; a title nobody scored has none in every score.
class ScoreDistribution(
    votes: Map<Int, Int>,
) {
    private val votes: List<Int> = SCORES.map { votes[it] ?: 0 }

    init {
        require(votes.keys.all { it in SCORES } && votes.values.all { it >= 0 })
    }

    operator fun get(score: Int): Int = votes[score - SCORES.first]

    val total: Int get() = votes.sum()

    override fun equals(other: Any?): Boolean = other is ScoreDistribution && other.votes == votes

    override fun hashCode(): Int = votes.hashCode()

    override fun toString(): String = "ScoreDistribution($votes)"

    companion object {
        val SCORES: IntRange = 1..10
    }
}

// Where a title's score distribution comes from: a service apart from the title's page (section 2.3).
fun interface ScoreDistributionSource {
    // Null only when the service has no record of the title; what it could not find out is a failure.
    suspend fun distribution(id: AnimeId): Outcome<ScoreDistribution?>
}

// A title's score distribution as a screen reads it, beside its page and never part of the page's own outcome.
interface ScoreDistributionRepository {
    suspend fun distribution(id: AnimeId): Outcome<ScoreDistribution?>
}

// Each answer kept in memory for the process, a "no record" too, so a title is asked once; a failure is not kept.
class KeptScoreDistributions(
    private val source: ScoreDistributionSource,
) : ScoreDistributionRepository {
    private val kept = ConcurrentHashMap<AnimeId, Kept>()
    private val asking = ConcurrentHashMap<AnimeId, Mutex>()

    private class Kept(
        val distribution: ScoreDistribution?,
    )

    // Asks made at once for one title wait for one request.
    override suspend fun distribution(id: AnimeId): Outcome<ScoreDistribution?> {
        kept[id]?.let { return Outcome.Success(it.distribution) }
        return asking.getOrPut(id, ::Mutex).withLock {
            kept[id]?.let { return@withLock Outcome.Success(it.distribution) }
            source.distribution(id).also { if (it is Outcome.Success) kept[id] = Kept(it.value) }
        }
    }
}

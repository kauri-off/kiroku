package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.score.ScoreDistribution
import kotlin.math.exp
import kotlin.math.roundToInt

// The fixtures' score distributions: around each title's MAL score, from the members who completed or watch it; none for a
// title not scored yet, and no record of the one title whose premiere is unannounced.
internal object FixtureScores {
    fun of(
        anime: List<Anime>,
        details: Map<AnimeId, AnimeDetails>,
    ): Map<AnimeId, ScoreDistribution> =
        anime
            .filter { it.id != FixtureAnime.premiereUnannounced.id }
            .associate { it.id to distribution(it.meanScore, details[it.id]) }

    private fun distribution(
        mean: Double?,
        details: AnimeDetails?,
    ): ScoreDistribution {
        mean ?: return ScoreDistribution(emptyMap())
        val statuses = details?.statistics?.statuses
        val voters = statuses?.let { it.completed + it.dropped + it.watching / 2 }?.takeIf { it > 0 } ?: VOTERS
        val weights = ScoreDistribution.SCORES.associateWith { exp(-(it - mean) * (it - mean) / SPREAD) + if (it == 1) TAIL else 0.0 }
        val sum = weights.values.sum()
        return ScoreDistribution(weights.mapValues { (voters * it.value / sum).roundToInt() })
    }

    private const val VOTERS = 12_000
    private const val SPREAD = 3.6
    private const val TAIL = 0.004
}

package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Titles

// Every list status, MAL airing status and watched count around an aired count of 2 out of 24, and airing shapes.
object AiringGrid {
    private val now = Fixtures.reference.now
    private const val GRID_ID = 990_000L
    private const val DAY = 86_400L

    val entries: List<ListEntry> =
        ListStatus.entries.flatMap { status ->
            AiringStatus.entries.flatMap { airingStatus ->
                (0..3).map { watched ->
                    val anime =
                        FixtureAnime.exactToWatch.copy(
                            id = AnimeId(GRID_ID + status.ordinal * 100L + airingStatus.ordinal * 10L + watched),
                            titles = Titles("$watched ${airingStatus.name} ${status.name}"),
                        )
                    ListEntry(anime.copy(airingStatus = airingStatus), ListValues(status, watched, null, null, null), now)
                }
            }
        }

    val airings: List<Airing?> =
        listOf(
            null,
            Airing(AiredCount.Unknown, 24, null, null),
            Airing(AiredCount.Exact(0), 24, ScheduledEpisode(1, now.plusSeconds(DAY)), null),
            Airing(AiredCount.Exact(2), 24, ScheduledEpisode(3, now.plusSeconds(DAY)), now.minusSeconds(DAY)),
            Airing(AiredCount.Exact(2), 24, null, now.minusSeconds(DAY)),
            Airing(AiredCount.Estimated(2), 24, null, now.minusSeconds(DAY)),
        )
}

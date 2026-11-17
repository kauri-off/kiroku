package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.Titles
import java.time.LocalDate

// Every list status, MAL airing status (an unknown one too) and watched count around 2 aired of 24, and stored data of every kind.
object AiringGrid {
    val now = Fixtures.reference.now
    private const val GRID_ID = 990_000L

    private val airingStatuses = AiringStatus.known + AiringStatus.Unrecognized("on_hiatus")

    // A title whose weekly slot has passed twice since MAL's start date.
    private val title = FixtureAnime.exactToWatch.copy(startDate = PartialDate.Day(LocalDate.parse("2026-01-03")))

    // The last of each status is a title whose total MAL does not know.
    val entries: List<ListEntry> =
        ListStatus.entries.flatMap { status ->
            airingStatuses.withIndex().flatMap { (index, airingStatus) ->
                (0..3).map { watched ->
                    val anime =
                        title.copy(
                            id = AnimeId(GRID_ID + status.ordinal * 100L + index * 10L + watched),
                            titles = Titles("$watched $airingStatus ${status.name}"),
                        )
                    ListEntry(anime.copy(airingStatus = airingStatus), ListValues(status, watched, null, null, null), now)
                }
            } +
                ListEntry(
                    title.copy(
                        id = AnimeId(GRID_ID + status.ordinal * 100L + 99),
                        titles = Titles("No total ${status.name}"),
                        malEpisodes = null,
                    ),
                    ListValues(status, 1, null, null, null),
                    now,
                )
        }

    private fun weekly(
        numbers: IntRange,
        firstAt: String,
        total: Int? = 24,
    ) = AiringSchedule(FixtureTime.weekly(numbers.first, FixtureTime.japan(firstAt), numbers), total)

    // Nothing; a premiere to come; two aired and more to come; a break; stopped; numbered on; totals that disagree; a recap; in doubt.
    val schedules: List<AiringSchedule?> =
        listOf(
            null,
            weekly(1..24, "2026-01-17T23:30"),
            weekly(1..24, "2026-01-03T23:30"),
            weekly(1..2, "2026-01-03T23:30"),
            weekly(1..2, "2025-12-06T23:30"),
            weekly(25..48, "2026-01-03T23:30"),
            weekly(1..2, "2026-01-03T23:30", total = 48),
            weekly(1..25, "2025-07-26T23:30"),
            weekly(1..1, "2026-01-03T23:30").copy(doubtedOver = 2),
        )

    // The title; without MAL's weekly slot, so with no estimate; the same before it airs; and finished.
    private val titles: List<Anime> =
        listOf(
            title,
            title.copy(broadcast = null),
            title.copy(broadcast = null, airingStatus = AiringStatus.NotYetAired),
            title.copy(airingStatus = AiringStatus.Finished),
        )

    // Every result for a title that airs, and none: a title whose airing was not computed.
    val airings: List<Airing?> =
        (listOf(null) + titles.flatMap { anime -> schedules.map { airingAt(anime, it, now) } }).distinct()
}

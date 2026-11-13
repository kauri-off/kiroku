package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
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
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth

class ListOrderTest {
    private val set = Fixtures.reference
    private val today = set.now.atZone(FixtureTime.zone).toLocalDate()

    private fun ordered(
        status: ListStatus,
        sort: ListSort,
    ): List<Anime> =
        order(set.entries.filter { it.status == status }, sort, set.airing::get, today) { it.anime.titles.romaji }.map { it.anime }

    @Test
    fun `A1 up next puts episodes to watch first, then the next episode, then the last update`() {
        assertEquals(
            listOf(
                FixtureAnime.exactToWatch,
                FixtureAnime.unknownTotal,
                FixtureAnime.longTitle,
                FixtureAnime.estimatedToWatch,
                FixtureAnime.exactCaughtUp,
                FixtureAnime.exactCaughtUpLater,
                FixtureAnime.oneShortOfTotal,
                FixtureAnime.estimatedCaughtUp,
                FixtureAnime.exactOnBreak,
                FixtureAnime.airedUnknown,
                FixtureAnime.finishedPartWay,
            ),
            ordered(ListStatus.Watching, ListSort.UpNext),
        )
    }

    @Test
    fun `up next puts a title with episodes to watch but no known latest air time at the end of its group`() {
        val known = entry("B", Instant.EPOCH)
        val unknown = entry("A", Instant.EPOCH)
        val airing =
            mapOf(
                known.anime.id to Airing(AiredCount.Exact(3), 12, null, Instant.parse("2026-01-01T00:00:00Z")),
                unknown.anime.id to Airing(AiredCount.Estimated(3), 12, null, null),
            )
        assertEquals(listOf(known, unknown), order(listOf(unknown, known), ListSort.UpNext, airing::get, today) { it.anime.titles.romaji })
    }

    @Test
    fun `equal keys are ordered by title`() {
        val updated = Instant.parse("2026-01-01T00:00:00Z")
        val b = entry("beta", updated)
        val a = entry("Alpha", updated)
        val c = entry("gamma", updated)
        assertEquals(listOf(a, b, c), order(listOf(c, b, a), ListSort.LastUpdated, { null }, today) { it.anime.titles.romaji })
        assertEquals(listOf(a, b, c), order(listOf(c, b, a), ListSort.UpNext, { null }, today) { it.anime.titles.romaji })
    }

    @Test
    fun `next episodes go soonest first`() {
        val later = entry("A", Instant.EPOCH)
        val sooner = entry("B", Instant.EPOCH)

        fun next(at: String) = Airing(AiredCount.Exact(1), 12, ScheduledEpisode(2, Instant.parse(at)), Instant.EPOCH.plusSeconds(1))
        val airing = mapOf(later.anime.id to next("2026-02-01T00:00:00Z"), sooner.anime.id to next("2026-01-20T00:00:00Z"))
        val watched = { e: ListEntry -> e.copy(values = e.values.copy(watchedEpisodes = 1)) }
        val entries = listOf(watched(later), watched(sooner))
        assertEquals(listOf(entries[1], entries[0]), order(entries, ListSort.UpNext, airing::get, today) { it.anime.titles.romaji })
    }

    @Test
    fun `a title MAL calls finished is not up next for a next episode the row does not show`() {
        val finished = entry("A", Instant.EPOCH).let { it.copy(anime = it.anime.copy(airingStatus = AiringStatus.Finished)) }
        val newer = entry("B", Instant.EPOCH.plusSeconds(1))
        val ahead = Airing(AiredCount.Exact(1), 12, ScheduledEpisode(2, Instant.parse("2026-01-20T00:00:00Z")), Instant.EPOCH)
        val watched = { e: ListEntry -> e.copy(values = e.values.copy(watchedEpisodes = 1)) }
        val entries = listOf(watched(newer), watched(finished))
        val airing = mapOf(finished.anime.id to ahead)
        assertEquals(entries, order(entries.reversed(), ListSort.UpNext, airing::get, today) { it.anime.titles.romaji })
    }

    @Test
    fun `a premiere at a known time is up next with the next episodes`() {
        val premiere = entry("A", Instant.EPOCH).let { it.copy(anime = it.anime.copy(airingStatus = AiringStatus.NotYetAired)) }
        val later = entry("B", Instant.EPOCH)
        val updated = entry("C", Instant.parse("2026-01-01T00:00:00Z"))
        val airing =
            mapOf(
                premiere.anime.id to Airing(AiredCount.Exact(0), 12, ScheduledEpisode(1, Instant.parse("2026-01-18T00:00:00Z")), null),
                later.anime.id to
                    Airing(
                        AiredCount.Exact(1),
                        12,
                        ScheduledEpisode(2, Instant.parse("2026-01-19T00:00:00Z")),
                        Instant.EPOCH,
                    ),
            )
        val entries = listOf(premiere, later.copy(values = later.values.copy(watchedEpisodes = 1)), updated)
        assertEquals(entries, order(entries.reversed(), ListSort.UpNext, airing::get, today) { it.anime.titles.romaji })
    }

    @Test
    fun `score goes highest first and unscored last`() {
        assertEquals(
            listOf(
                FixtureAnime.completedOva,
                FixtureAnime.prequel,
                FixtureAnime.film,
                FixtureAnime.completedSpecial,
                FixtureAnime.completedOna,
                FixtureAnime.completedUnscored,
            ),
            ordered(ListStatus.Completed, ListSort.Score),
        )
    }

    @Test
    fun `start date goes earliest first and an unknown date last`() {
        assertEquals(
            listOf(
                FixtureAnime.plannedFinished,
                FixtureAnime.plannedAiring,
                FixtureAnime.premiereTimeSoon,
                FixtureAnime.premiereTimeLater,
                FixtureAnime.premiereDate,
                FixtureAnime.premiereMonth,
                FixtureAnime.premiereYear,
                FixtureAnime.premiereUnannounced,
            ),
            ordered(ListStatus.Planned, ListSort.StartDate),
        )
    }

    @Test
    fun `up next is offered and the default only in watching with airing data`() {
        assertEquals(ListSort.entries, sortsFor(ListStatus.Watching, withAiring = true))
        assertEquals(ListSort.UpNext, defaultSort(ListStatus.Watching, withAiring = true))
        assertEquals(ListSort.LastUpdated, defaultSort(ListStatus.Watching, withAiring = false))
        ListStatus.entries.filter { it != ListStatus.Watching }.forEach { status ->
            assertEquals(ListSort.entries - ListSort.UpNext, sortsFor(status, withAiring = true))
            assertEquals(ListSort.LastUpdated, defaultSort(status, withAiring = true))
        }
    }

    @Test
    fun `each sort's default direction is its own order and up next has none`() {
        assertEquals(
            mapOf(
                ListSort.UpNext to null,
                ListSort.Title to SortDirection.Ascending,
                ListSort.LastUpdated to SortDirection.Descending,
                ListSort.Score to SortDirection.Descending,
                ListSort.StartDate to SortDirection.Ascending,
            ),
            ListSort.entries.associateWith { it.defaultDirection },
        )
    }

    @Test
    fun `title goes A to Z or Z to A, and equal titles keep one order in both`() {
        val b = entry("beta", Instant.EPOCH)
        val a = entry("Alpha", Instant.EPOCH)
        val g = entry("gamma", Instant.EPOCH)
        val same1 = entry("Same", Instant.EPOCH)
        val same2 = entry("same", Instant.EPOCH)
        val entries = listOf(same2, g, same1, a, b)
        assertEquals(listOf(a, b, g, same1, same2), sorted(entries, ListSort.Title, SortDirection.Ascending))
        assertEquals(listOf(same1, same2, g, b, a), sorted(entries, ListSort.Title, SortDirection.Descending))
    }

    @Test
    fun `last updated goes newest or oldest first, equal times by title`() {
        val old = entry("Z old", Instant.parse("2025-01-01T00:00:00Z"))
        val tieB = entry("B tie", Instant.parse("2025-06-01T00:00:00Z"))
        val tieA = entry("A tie", Instant.parse("2025-06-01T00:00:00Z"))
        val new = entry("M new", Instant.parse("2026-01-01T00:00:00Z"))
        val entries = listOf(tieB, old, new, tieA)
        assertEquals(listOf(new, tieA, tieB, old), sorted(entries, ListSort.LastUpdated, SortDirection.Descending))
        assertEquals(listOf(old, tieA, tieB, new), sorted(entries, ListSort.LastUpdated, SortDirection.Ascending))
    }

    @Test
    fun `score goes highest or lowest first, unscored last in both, equal scores by title`() {
        val unscoredA = entry("A", Instant.EPOCH, score = null)
        val unscoredB = entry("B", Instant.EPOCH, score = null)
        val sevenD = entry("D", Instant.EPOCH, score = 7)
        val sevenC = entry("C", Instant.EPOCH, score = 7)
        val nine = entry("E", Instant.EPOCH, score = 9)
        val four = entry("F", Instant.EPOCH, score = 4)
        val entries = listOf(unscoredB, sevenD, unscoredA, four, nine, sevenC)
        assertEquals(listOf(nine, sevenC, sevenD, four, unscoredA, unscoredB), sorted(entries, ListSort.Score, SortDirection.Descending))
        assertEquals(listOf(four, sevenC, sevenD, nine, unscoredA, unscoredB), sorted(entries, ListSort.Score, SortDirection.Ascending))
    }

    @Test
    fun `start date goes earliest or latest first, an unknown date last in both, equal dates by title`() {
        val unknownA = entry("A", Instant.EPOCH, start = null)
        val unknownB = entry("B", Instant.EPOCH, start = null)
        val year = entry("C", Instant.EPOCH, start = PartialDate.OnlyYear(Year.of(2019)))
        val dayE = entry("E", Instant.EPOCH, start = PartialDate.Day(LocalDate.of(2025, 10, 1)))
        val monthD = entry("D", Instant.EPOCH, start = PartialDate.Month(YearMonth.of(2025, 10)))
        val late = entry("F", Instant.EPOCH, start = PartialDate.Day(LocalDate.of(2026, 1, 9)))
        val entries = listOf(unknownB, late, dayE, unknownA, year, monthD)
        assertEquals(listOf(year, monthD, dayE, late, unknownA, unknownB), sorted(entries, ListSort.StartDate, SortDirection.Ascending))
        assertEquals(listOf(late, monthD, dayE, year, unknownA, unknownB), sorted(entries, ListSort.StartDate, SortDirection.Descending))
    }

    @Test
    fun `without a direction each sort keeps its own order, and up next ignores one`() {
        val entries = set.entries
        ListSort.entries.forEach { sort ->
            assertEquals(
                order(entries, sort, set.airing::get, today, sort.defaultDirection) { it.anime.titles.romaji },
                order(entries, sort, set.airing::get, today) { it.anime.titles.romaji },
            )
        }
        SortDirection.entries.forEach { direction ->
            assertEquals(
                order(entries, ListSort.UpNext, set.airing::get, today) { it.anime.titles.romaji },
                order(entries, ListSort.UpNext, set.airing::get, today, direction) { it.anime.titles.romaji },
            )
        }
    }

    private fun sorted(
        entries: List<ListEntry>,
        sort: ListSort,
        direction: SortDirection,
    ): List<ListEntry> = order(entries, sort, { null }, today, direction) { it.anime.titles.romaji }

    private var nextId = 1L

    private fun entry(
        title: String,
        updated: Instant,
        score: Int? = null,
        start: PartialDate? = FixtureAnime.exactToWatch.startDate,
    ): ListEntry {
        val anime = FixtureAnime.exactToWatch.copy(id = AnimeId(nextId++), titles = Titles(title), startDate = start)
        return ListEntry(anime, ListValues(ListStatus.Watching, 0, score, null, null), updated)
    }
}

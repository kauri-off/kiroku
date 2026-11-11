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
import io.github.kauri_off.kiroku.core.model.Titles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

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

    private var nextId = 1L

    private fun entry(
        title: String,
        updated: Instant,
    ): ListEntry {
        val anime = FixtureAnime.exactToWatch.copy(id = AnimeId(nextId++), titles = Titles(title))
        return ListEntry(anime, ListValues(ListStatus.Watching, 0, null, null, null), updated)
    }
}

package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.premiereOf
import io.github.kauri_off.kiroku.core.airing.toWatch
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class FixtureCasesTest {
    private enum class RowCase {
        ExactToWatch,
        ExactCaughtUpWithNext,
        ExactCaughtUpWithoutNext,
        EstimatedToWatch,
        EstimatedCaughtUp,
        Finished,
        AiredUnknown,
        PremiereTime,
        PremiereDate,
        PremiereMonth,
        PremiereYear,
        PremiereUnannounced,
        CompletedScored,
        CompletedUnscored,
    }

    private enum class AiringBlockCase {
        ExactToWatch,
        ExactCaughtUp,
        ExactNotOnList,
        EstimatedToWatch,
        EstimatedCaughtUp,
        AiredUnknown,
    }

    private val entries = Fixtures.entries

    private fun airing(anime: Anime): Airing? = Fixtures.airing[anime.id]

    private fun rowCase(entry: ListEntry): RowCase {
        val anime = entry.anime
        if (entry.status == ListStatus.Completed) {
            return if (entry.values.score != null) RowCase.CompletedScored else RowCase.CompletedUnscored
        }
        premiereOf(anime, airing(anime))?.let { return premiereCase(it) }
        if (anime.airingStatus == AiringStatus.Finished) return RowCase.Finished
        val airing = airing(anime) ?: error("no airing data for ${anime.titles.romaji}")
        val toWatch = toWatch(entry, airing)?.value ?: 0
        return when (airing.aired) {
            AiredCount.Unknown -> RowCase.AiredUnknown
            is AiredCount.Estimated -> if (toWatch > 0) RowCase.EstimatedToWatch else RowCase.EstimatedCaughtUp
            is AiredCount.Exact ->
                when {
                    toWatch > 0 -> RowCase.ExactToWatch
                    airing.nextEpisode != null -> RowCase.ExactCaughtUpWithNext
                    else -> RowCase.ExactCaughtUpWithoutNext
                }
        }
    }

    private fun premiereCase(premiere: Premiere): RowCase =
        when (premiere) {
            is Premiere.At -> RowCase.PremiereTime
            is Premiere.OnDay -> RowCase.PremiereDate
            is Premiere.InMonth -> RowCase.PremiereMonth
            is Premiere.InYear -> RowCase.PremiereYear
            Premiere.Unannounced -> RowCase.PremiereUnannounced
        }

    private fun airingBlockCase(anime: Anime): AiringBlockCase? {
        if (anime.airingStatus != AiringStatus.Airing) return null
        val airing = airing(anime) ?: return null
        val entry = Fixtures.entryFor(anime.id)
        val toWatch = entry?.let { toWatch(it, airing) }?.value ?: 0
        return when (airing.aired) {
            AiredCount.Unknown -> AiringBlockCase.AiredUnknown
            is AiredCount.Estimated ->
                if (toWatch > 0) AiringBlockCase.EstimatedToWatch else AiringBlockCase.EstimatedCaughtUp
            is AiredCount.Exact ->
                when {
                    toWatch > 0 -> AiringBlockCase.ExactToWatch
                    entry == null -> AiringBlockCase.ExactNotOnList
                    else -> AiringBlockCase.ExactCaughtUp
                }
        }
    }

    @Test
    fun `about thirty list entries with distinct titles`() {
        assertTrue(entries.size in 28..32)
        assertEquals(entries.size, entries.map { it.anime.id }.toSet().size)
    }

    @Test
    fun `every list status is present`() {
        assertEquals(ListStatus.entries.toSet(), entries.map { it.status }.toSet())
    }

    @Test
    fun `every row case of the list is present`() {
        val present = entries.map(::rowCase).toSet()
        assertEquals(RowCase.entries.toSet(), present)
    }

    @Test
    fun `the watching tab has every airing row case`() {
        val watching = entries.filter { it.status == ListStatus.Watching }.map(::rowCase).toSet()
        val airingCases =
            setOf(
                RowCase.ExactToWatch,
                RowCase.ExactCaughtUpWithNext,
                RowCase.ExactCaughtUpWithoutNext,
                RowCase.EstimatedToWatch,
                RowCase.EstimatedCaughtUp,
                RowCase.Finished,
                RowCase.AiredUnknown,
            )
        assertTrue(watching.containsAll(airingCases))
    }

    @Test
    fun `an airing title outside watching has nothing to watch`() {
        val others = entries.filter { it.status != ListStatus.Watching && it.anime.airingStatus == AiringStatus.Airing }
        assertTrue(others.isNotEmpty())
        others.forEach { assertEquals(null, toWatch(it, airing(it.anime))) }
    }

    @Test
    fun `the planned tab has the premiere forms the list screen renders`() {
        val planned = entries.filter { it.status == ListStatus.Planned }.map(::rowCase).toSet()
        assertTrue(planned.containsAll(listOf(RowCase.PremiereTime, RowCase.PremiereDate, RowCase.PremiereUnannounced)))
    }

    @Test
    fun `a premiere time within six days and one beyond them`() {
        val gaps =
            Fixtures.anime.mapNotNull { anime ->
                (premiereOf(anime, airing(anime)) as? Premiere.At)?.let { Duration.between(FixtureTime.now, it.instant) }
            }
        assertTrue(gaps.any { it > Duration.ZERO && it < Duration.ofDays(6) })
        assertTrue(gaps.any { it > Duration.ofDays(7) })
    }

    @Test
    fun `a very long title on the watching tab with lines two and three`() {
        val entry = entries.single { it.anime.titles.romaji.length > 100 }
        assertEquals(ListStatus.Watching, entry.status)
        assertEquals(RowCase.ExactToWatch, rowCase(entry))
    }

    @Test
    fun `a title with an unknown episode count`() {
        assertTrue(entries.any { it.status == ListStatus.Watching && it.anime.episodes == null && airing(it.anime)?.total == null })
    }

    @Test
    fun `a watching title one episode short of its total`() {
        assertTrue(
            entries.any {
                it.status == ListStatus.Watching && it.anime.episodes == it.values.watchedEpisodes + 1
            },
        )
    }

    @Test
    fun `a film on the list`() {
        assertTrue(entries.any { it.anime.mediaType == MediaType.Movie && it.anime.episodes == 1 })
    }

    @Test
    fun `every case of the airing block`() {
        val present = Fixtures.anime.mapNotNull(::airingBlockCase).toSet()
        assertEquals(AiringBlockCase.entries.toSet(), present)
    }

    @Test
    fun `every premiere form of a not yet aired title`() {
        val forms =
            Fixtures.anime
                .mapNotNull { premiereOf(it, airing(it)) }
                .map { it::class }
                .toSet()
        assertEquals(
            setOf(
                Premiere.At::class,
                Premiere.OnDay::class,
                Premiere.InMonth::class,
                Premiere.InYear::class,
                Premiere.Unannounced::class,
            ),
            forms,
        )
    }

    @Test
    fun `the fully described title`() {
        val full = Fixtures.details.getValue(FixtureAnime.exactToWatch.id)
        val anime = FixtureAnime.exactToWatch
        assertNotNull(full.synopsis)
        assertTrue(full.genres.isNotEmpty())
        assertNotNull(full.source)
        assertNotNull(full.rating)
        assertNotNull(full.episodeDuration)
        assertNotNull(full.season)
        assertNotNull(anime.broadcast)
        assertNotNull(anime.meanScore)
        assertNotNull(anime.rank)
        assertTrue(anime.titles.synonyms.isNotEmpty())
        assertTrue(full.studios.isNotEmpty())
        val statistics = checkNotNull(full.statistics)
        ListStatus.entries.forEach { assertTrue(statistics.statuses[it] > 0) }
        assertEquals(AiringBlockCase.ExactToWatch, airingBlockCase(anime))
    }

    @Test
    fun `twelve related titles with every relation type`() {
        val related = Fixtures.details.getValue(FixtureAnime.exactToWatch.id).related
        assertEquals(12, related.size)
        assertEquals(RelationType.known.toSet(), related.map { it.relation }.toSet())
        assertTrue(related.any { it.relation == RelationType.AlternativeSetting })
        assertTrue(related.any { it.relation == RelationType.AlternativeVersion })
        assertTrue(related.count { Fixtures.entryFor(it.anime.id) != null } >= 2)
        assertTrue(related.any { Fixtures.entryFor(it.anime.id)?.status == ListStatus.Planned })
        assertTrue(related.all { it.anime in Fixtures.anime })
    }

    @Test
    fun `a sparse title without score rank or related titles`() {
        val sparse = Fixtures.details.getValue(FixtureAnime.premiereUnannounced.id)
        assertTrue(sparse.related.isEmpty())
        assertEquals(null, FixtureAnime.premiereUnannounced.meanScore)
        assertEquals(null, FixtureAnime.premiereUnannounced.rank)
        assertEquals(null, sparse.synopsis)
    }

    @Test
    fun `one user with statistics`() {
        val statistics = Fixtures.userStatistics
        assertTrue(Fixtures.user.name.isNotBlank())
        assertTrue(statistics.statuses.total > 0)
        assertNotNull(statistics.meanScore)
        assertTrue(statistics.scores.isNotEmpty())
        assertTrue(statistics.genres.isNotEmpty())
        assertTrue(statistics.mediaTypes.isNotEmpty())
        assertTrue(statistics.years.isNotEmpty())
        assertEquals(statistics.statuses.total, statistics.mediaTypes.sumOf { it.count })
        assertEquals(statistics.statuses.total, statistics.years.values.sum())
    }

    @Test
    fun `every title has details and every airing title has airing data`() {
        Fixtures.anime.forEach { assertNotNull(Fixtures.details[it.id]) }
        Fixtures.anime.filter { it.airingStatus == AiringStatus.Airing }.forEach { assertNotNull(airing(it)) }
        assertTrue(Fixtures.airing.keys.all { id -> Fixtures.anime.any { it.id == id } })
    }
}

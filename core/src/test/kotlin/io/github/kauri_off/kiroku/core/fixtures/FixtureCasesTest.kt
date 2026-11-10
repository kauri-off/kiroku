package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.episodeTotal
import io.github.kauri_off.kiroku.core.airing.premiereOf
import io.github.kauri_off.kiroku.core.airing.toWatch
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.relatedStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(Parameterized::class)
class FixtureCasesTest(
    @Suppress("unused") private val name: String,
    private val set: FixtureSet,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun sets(): List<Array<Any>> = FixtureSets.parameters()
    }

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

    private val entries = set.entries

    private fun airing(anime: Anime): Airing? = set.airing[anime.id]

    private fun tab(status: ListStatus) = entries.filter { it.status == status }

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
        val airing = airing(anime) ?: return null
        if (anime.airingStatus == AiringStatus.Finished || premiereOf(anime, airing) != null) return null
        val entry = set.entryFor(anime.id)
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

    private fun daysFromToday(instant: Instant): Long =
        ChronoUnit.DAYS.between(
            set.now.atZone(FixtureTime.zone).toLocalDate(),
            instant.atZone(FixtureTime.zone).toLocalDate(),
        )

    private fun nextEpisodeDays(entries: List<ListEntry>): List<Long> =
        entries
            .filter { rowCase(it) == RowCase.ExactCaughtUpWithNext }
            .map { daysFromToday(checkNotNull(airing(it.anime)?.nextEpisode).airsAt) }

    private fun premiereDays(): List<Long> =
        set.anime.mapNotNull { anime ->
            (premiereOf(anime, airing(anime)) as? Premiere.At)?.let { daysFromToday(it.instant) }
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
    fun `the watching tab has every airing row case`() {
        val watching = tab(ListStatus.Watching).map(::rowCase).toSet()
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
        assertEquals(airingCases, watching)
    }

    @Test
    fun `the planned tab has every premiere form`() {
        val planned = tab(ListStatus.Planned).map(::rowCase).toSet()
        val premieres =
            setOf(
                RowCase.PremiereTime,
                RowCase.PremiereDate,
                RowCase.PremiereMonth,
                RowCase.PremiereYear,
                RowCase.PremiereUnannounced,
            )
        assertTrue(planned.containsAll(premieres))
    }

    @Test
    fun `the completed tab has a scored and an unscored title`() {
        assertEquals(
            setOf(RowCase.CompletedScored, RowCase.CompletedUnscored),
            tab(ListStatus.Completed).map(::rowCase).toSet(),
        )
    }

    @Test
    fun `an airing title outside watching has nothing to watch`() {
        val others = entries.filter { it.status != ListStatus.Watching && it.anime.airingStatus == AiringStatus.Airing }
        assertTrue(others.isNotEmpty())
        others.forEach { assertNull(toWatch(it, airing(it.anime))) }
    }

    @Test
    fun `watching has next episodes today and later in the week, so up next has an order to show`() {
        val days = nextEpisodeDays(tab(ListStatus.Watching))
        assertTrue(days.contains(0L))
        assertTrue(days.any { it in 2L..6L })
    }

    @Test
    fun `an airing title outside watching has its next episode tomorrow`() {
        val others = entries.filter { it.status != ListStatus.Watching }
        assertTrue(nextEpisodeDays(others).contains(1L))
    }

    @Test
    fun `a premiere time within six days and one beyond them`() {
        val days = premiereDays()
        assertTrue(days.any { it in 2L..6L })
        assertTrue(days.any { it > 6L })
    }

    @Test
    fun `a very long title on the watching tab with lines two and three`() {
        val entry = entries.single { it.anime.titles.romaji.length > 100 }
        assertEquals(ListStatus.Watching, entry.status)
        assertEquals(RowCase.ExactToWatch, rowCase(entry))
    }

    @Test
    fun `a long romaji title with no space to break on`() {
        assertTrue(entries.any { it.anime.titles.romaji.length > 40 && ' ' !in it.anime.titles.romaji })
    }

    @Test
    fun `a title with an unknown episode count`() {
        assertTrue(
            tab(ListStatus.Watching).any { episodeTotal(it.anime, airing(it.anime)) == null && airing(it.anime) != null },
        )
    }

    @Test
    fun `a watching title one episode short of its total`() {
        assertTrue(tab(ListStatus.Watching).any { it.anime.malEpisodes == it.values.watchedEpisodes + 1 })
    }

    @Test
    fun `a scored title in watching`() {
        assertTrue(tab(ListStatus.Watching).any { it.values.score != null && rowCase(it) == RowCase.ExactToWatch })
    }

    @Test
    fun `filtering watching by a short query gives two titles`() {
        assertEquals(
            2,
            tab(ListStatus.Watching).count {
                it.anime.titles.romaji
                    .contains("kaze", ignoreCase = true)
            },
        )
    }

    @Test
    fun `a film on the list`() {
        assertTrue(entries.any { it.anime.mediaType == MediaType.Movie && it.anime.malEpisodes == 1 })
    }

    @Test
    fun `a finished title without a score or rank and one with a five-digit rank`() {
        assertTrue(
            entries.any { it.anime.airingStatus == AiringStatus.Finished && it.anime.meanScore == null && it.anime.rank == null },
        )
        assertTrue(entries.any { (it.anime.rank ?: 0) >= 10_000 })
    }

    @Test
    fun `a title whose media type MAL does not know`() {
        assertTrue(entries.any { it.anime.mediaType == null && premiereOf(it.anime, airing(it.anime)) != null })
    }

    @Test
    fun `every case of the airing block`() {
        val present = set.anime.mapNotNull(::airingBlockCase).toSet()
        assertEquals(AiringBlockCase.entries.toSet(), present)
    }

    @Test
    fun `every premiere form of a not yet aired title`() {
        val forms =
            set.anime
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
        val anime = set.anime(FixtureAnime.exactToWatch.id)
        val full = set.details.getValue(anime.id)
        assertNotNull(full.synopsis)
        assertTrue(anime.genres.isNotEmpty())
        assertNotNull(full.source)
        assertNotNull(full.rating)
        assertNotNull(anime.averageEpisodeDuration)
        assertNotNull(full.season)
        assertNotNull(full.endDate)
        assertNotNull(anime.broadcast)
        assertNotNull(anime.meanScore)
        assertNotNull(anime.rank)
        assertNotNull(anime.titles.english)
        assertNotNull(anime.titles.japanese)
        assertTrue(anime.titles.synonyms.isNotEmpty())
        assertTrue(full.studios.isNotEmpty())
        val statistics = checkNotNull(full.statistics)
        ListStatus.entries.forEach { assertTrue(statistics.statuses[it] > 0) }
        assertEquals(AiringBlockCase.ExactToWatch, airingBlockCase(anime))
    }

    @Test
    fun `twelve related titles with every relation type`() {
        val related = set.details.getValue(FixtureAnime.exactToWatch.id).related
        assertEquals(12, related.size)
        assertEquals(RelationType.known.toSet(), related.map { it.relation }.toSet())
        assertTrue(related.count { set.entryFor(it.anime.id) != null } >= 2)
        assertTrue(related.any { set.entryFor(it.anime.id)?.status == ListStatus.Planned })
        assertTrue(related.all { it.anime in set.anime })
        val strip = relatedStrip(related)
        assertTrue(RelationType.AlternativeSetting in strip.map { it.relation })
        assertTrue(RelationType.AlternativeVersion in strip.map { it.relation })
        assertTrue(strip.size < related.size)
    }

    @Test
    fun `a title with exactly one related title that links back`() {
        val single = set.details.values.filter { it.related.size == 1 }
        assertTrue(single.isNotEmpty())
        single.forEach { details ->
            val target =
                set.details.getValue(
                    details.related
                        .single()
                        .anime.id,
                )
            assertTrue(target.related.any { it.anime.id == details.id })
        }
    }

    @Test
    fun `a sparse title that still has a few facts`() {
        val anime = set.anime(FixtureAnime.premiereUnannounced.id)
        val sparse = set.details.getValue(anime.id)
        val facts =
            listOf(
                anime.titles.english,
                anime.titles.japanese,
                anime.titles.synonyms.firstOrNull(),
                anime.startDate,
                sparse.endDate,
                sparse.season,
                sparse.source,
                sparse.studios.firstOrNull(),
                sparse.rating,
                anime.averageEpisodeDuration,
                anime.broadcast,
            )
        assertTrue(facts.count { it != null } in 1..3)
        assertTrue(sparse.related.isEmpty())
        assertNull(anime.meanScore)
        assertNull(anime.rank)
        assertNull(sparse.synopsis)
    }

    @Test
    fun `synopses vary in length and a named case has many genres and studios`() {
        val lengths = set.details.values.mapNotNull { it.synopsis?.length }
        assertTrue(lengths.any { it < 60 })
        assertTrue(lengths.count { it > 400 } >= 2)
        assertTrue(set.details.values.none { it.synopsis?.contains("fixture", ignoreCase = true) == true })
        val notOnList = set.anime(FixtureAnime.notOnListAiring.id)
        assertTrue(notOnList.genres.size >= 6)
        assertTrue(
            set.details
                .getValue(notOnList.id)
                .studios.size >= 3,
        )
        assertTrue(
            set.details.values
                .flatMap { it.studios }
                .toSet()
                .size >= 6,
        )
    }

    @Test
    fun `title statistics are uneven and one title has over two million members`() {
        val statistics = set.details.values.mapNotNull { it.statistics }
        assertTrue(statistics.any { it.members > 2_000_000 })
        assertTrue(statistics.none { it.members % 1_000 == 0 && it.members > 10_000 })
        statistics.forEach { assertEquals(it.statuses.total, it.members) }
        val finishedShares =
            set.anime
                .filter { it.airingStatus == AiringStatus.Finished }
                .map { anime ->
                    val s = checkNotNull(set.details.getValue(anime.id).statistics)
                    s.statuses.completed * 100 / s.members
                }.toSet()
        assertTrue(finishedShares.size > 3)
    }

    @Test
    fun `every title has details and every airing title has airing data`() {
        set.anime.forEach { assertNotNull(set.details[it.id]) }
        set.anime.filter { it.airingStatus == AiringStatus.Airing }.forEach { assertNotNull(airing(it)) }
        assertTrue(set.airing.keys.all { id -> set.anime.any { it.id == id } })
    }
}

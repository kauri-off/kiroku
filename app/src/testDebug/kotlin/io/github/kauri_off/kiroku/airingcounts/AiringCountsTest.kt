package io.github.kauri_off.kiroku.airingcounts

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.airingAt
import io.github.kauri_off.kiroku.core.airing.tracked
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.rules.projectDir
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

@OptIn(ExperimentalCoroutinesApi::class)
class AiringCountsTest {
    private val set = Fixtures.reference

    private fun counts(
        entries: List<ListEntry> = set.entries,
        airing: Map<AnimeId, Airing> = set.airing,
    ) = airingCounts(entries, airing, set.schedules, TitleLanguage.Romaji)

    @Test
    fun `on the fixtures each count is the number of fixture titles of its kind, and the counts add up to the tracked titles`() {
        val kinds =
            with(FixtureAnime) {
                mapOf(
                    CountKind.Exact to
                        listOf(
                            exactToWatch,
                            exactCaughtUp,
                            exactCaughtUpLater,
                            exactOnBreak,
                            unknownTotal,
                            longTitle,
                            premiereTimeSoon,
                            premiereTimeLater,
                            plannedAiring,
                            onHoldAiring,
                        ),
                    CountKind.NumberedDifferently to listOf(estimatedToWatch),
                    CountKind.Doubt to listOf(estimatedCaughtUp),
                    CountKind.NoSchedule to listOf(premiereDate, premiereMonth, premiereYear, premiereUnannounced),
                    CountKind.Unknown to listOf(airedUnknown),
                )
            }
        val counts = counts()

        assertEquals(kinds.mapValues { it.value.size }, counts.counts)
        assertEquals(set.entries.count { it.tracked }, counts.tracked)
        assertEquals(
            set.entries
                .filter { it.tracked }
                .map { it.anime.id }
                .toSet(),
            kinds.values
                .flatten()
                .map { it.id }
                .toSet(),
        )
        assertEquals(
            kinds.filterKeys { it != CountKind.Exact }.flatMap { (kind, titles) -> titles.map { it.id to kind } }.toSet(),
            counts.notExact.map { it.id to it.kind }.toSet(),
        )
    }

    @Test
    fun `each title that is not exact is listed by kind, then title, with its count and what its stored schedule says`() {
        val listed = counts().notExact
        assertEquals(listed.sortedWith(compareBy<CountedTitle> { it.kind }.thenBy { it.title }), listed)

        val numbered = listed.single { it.id == FixtureAnime.estimatedToWatch.id }
        assertEquals(FixtureAnime.estimatedToWatch.titles.romaji, numbered.title)
        assertEquals(AiredCount.Estimated(10), numbered.aired)
        assertEquals(Why.NumberedFrom(13, 24), numbered.why)

        val doubted = listed.single { it.id == FixtureAnime.estimatedCaughtUp.id }
        assertEquals(AiredCount.Estimated(5), doubted.aired)
        assertEquals(Why.TotalsDiffer(12, 13), doubted.why)

        assertEquals(AiredCount.Unknown, listed.single { it.id == FixtureAnime.airedUnknown.id }.aired)
        assertTrue(listed.filter { it.kind == CountKind.NoSchedule || it.kind == CountKind.Unknown }.all { it.why == null })
    }

    @Test
    fun `a schedule that has stopped is doubt, with the time its last episode aired`() {
        val anime = FixtureAnime.exactOnBreak
        val lastAired = set.now.minus(Duration.ofDays(29))
        val stopped = AiringSchedule(listOf(ScheduledEpisode(1, lastAired.minus(Duration.ofDays(7))), ScheduledEpisode(2, lastAired)), 13)
        val counts =
            airingCounts(
                set.entries,
                set.airing + (anime.id to airingAt(anime, stopped, set.now)),
                set.schedules + (anime.id to stopped),
                TitleLanguage.Romaji,
            )

        val listed = counts.notExact.single { it.id == anime.id }
        assertEquals(CountKind.Doubt, listed.kind)
        assertEquals(AiredCount.Estimated(2), listed.aired)
        assertEquals(Why.StoppedAfter(lastAired), listed.why)
        assertEquals(2, counts.counts[CountKind.Doubt])
    }

    @Test
    fun `a tracked title without a result is counted unknown and said to have none, and a title that is not tracked is not counted`() {
        val counts = counts(airing = set.airing - FixtureAnime.exactToWatch.id)
        assertEquals(9, counts.counts[CountKind.Exact])
        assertEquals(2, counts.counts[CountKind.Unknown])
        val missing = counts.notExact.single { it.id == FixtureAnime.exactToWatch.id }
        assertEquals(CountKind.Unknown, missing.kind)
        assertEquals(Why.NoResult, missing.why)
        assertEquals(null, counts.notExact.single { it.id == FixtureAnime.airedUnknown.id }.why)
        assertTrue(counts.notExact.none { it.id == FixtureAnime.droppedEstimated.id })
    }

    // A5 by the numbers alone: the schedule states no total, and AniList's side is its highest number.
    @Test
    fun `a schedule with no stated total numbered past MAL's total shows its highest number as AniList's total`() {
        val anime = FixtureAnime.estimatedCaughtUp
        val schedule = set.schedules.getValue(anime.id).copy(totalEpisodes = null)
        val airing = airingAt(anime, schedule, set.now)
        val counts =
            airingCounts(set.entries, set.airing + (anime.id to airing), set.schedules + (anime.id to schedule), TitleLanguage.Romaji)

        val listed = counts.notExact.single { it.id == anime.id }
        assertEquals(CountKind.Doubt, listed.kind)
        assertEquals(Why.TotalsDiffer(12, 13), listed.why)
    }

    @Test
    fun `a title whose numbering AniList doubts is doubt with how far the other entry aired, and a reason before it keeps its own`() {
        val doubted = FixtureAnime.exactToWatch
        val totals = FixtureAnime.estimatedCaughtUp
        val schedules =
            set.schedules +
                (doubted.id to set.schedules.getValue(doubted.id).copy(doubtedOver = 20)) +
                (totals.id to set.schedules.getValue(totals.id).copy(doubtedOver = 2))
        val airing = set.airing + listOf(doubted, totals).associate { it.id to airingAt(it, schedules[it.id], set.now) }

        val listed = airingCounts(set.entries, airing, schedules, TitleLanguage.Romaji).notExact
        val first = listed.single { it.id == doubted.id }
        assertEquals(CountKind.Doubt, first.kind)
        assertEquals(AiredCount.Estimated(20), first.aired)
        assertEquals(Why.DoubtedOver(20), first.why)
        assertEquals(Why.TotalsDiffer(12, 13), listed.single { it.id == totals.id }.why)
    }

    @Test
    fun `the counts are read again when what is stored changes, and a list that cannot be read ends them as unreadable`() =
        runTest {
            val entries = MutableStateFlow(set.entries)
            val states = mutableListOf<AiringCountsState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                airingCountsState(Entries(entries), Stored(), TitleLanguage.Romaji).toList(states)
            }

            fun unknown() = (states.last() as AiringCountsState.Read).counts.counts[CountKind.Unknown]
            assertEquals(1, unknown())
            entries.value = set.entries.filter { it.anime.id != FixtureAnime.airedUnknown.id }
            assertEquals(0, unknown())

            val failing = flow<List<ListEntry>> { throw DataException(DataError.StorageError) }
            assertEquals(AiringCountsState.Unreadable, airingCountsState(Entries(failing), Stored(), TitleLanguage.Romaji).first())
        }

    @Test
    fun `the section's strings exist in English and in Russian`() {
        fun names(folder: String): Map<String, String> {
            val file = File(projectDir, "app/src/debug/res/$folder/strings_airing_counts.xml")
            val nodes =
                DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(file)
                    .getElementsByTagName("string")
            return (0 until nodes.length).associate {
                nodes
                    .item(
                        it,
                    ).attributes
                    .getNamedItem("name")
                    .nodeValue to nodes.item(it).textContent
            }
        }
        val english = names("values")
        val russian = names("values-ru")
        assertTrue(english.isNotEmpty())
        assertEquals(english.keys, russian.keys)
        val same = english.filter { (name, text) -> russian[name] == text }.keys
        assertEquals(setOf("airing_counts_separator"), same)
    }

    private class Entries(
        private val entries: Flow<List<ListEntry>>,
    ) : ListSource {
        override fun observeEntries() = entries

        override fun observeEntry(id: AnimeId) = throw UnsupportedOperationException()

        override fun observeEntriesOf(ids: Set<AnimeId>) = throw UnsupportedOperationException()

        override fun observeSyncedAt() = throw UnsupportedOperationException()

        override suspend fun sync() = throw UnsupportedOperationException()
    }

    // Answers every read from the fixtures and fails any call that would ask a source.
    private inner class Stored : AiringRepository {
        override fun observeAiring() = flowOf(set.airing)

        override fun observePageAiring(page: Anime) = throw UnsupportedOperationException()

        override fun observeSchedules() = throw UnsupportedOperationException()

        override fun observeStoredSchedules() = flowOf(set.schedules)

        override suspend fun refreshStale(): Outcome<Unit> = throw UnsupportedOperationException()

        override suspend fun refresh(): Outcome<Unit> = throw UnsupportedOperationException()

        override suspend fun refreshStale(page: Anime): Outcome<Unit> = throw UnsupportedOperationException()

        override suspend fun refresh(page: Anime): Outcome<Unit> = throw UnsupportedOperationException()
    }
}

package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.airing.AiredCount
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.Titles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class SubScreenStateTest {
    private val set = Fixtures.reference

    @Test
    fun everyRelatedAnimeIsShownInTheBudgetsGroupOrderAndByStartDateWithinAGroup() {
        val state = SubScreenFixtures.related(FixtureAnime.exactToWatch)
        assertEquals(RelationType.known, state.groups.map { it.relation })
        assertEquals(
            listOf(
                FixtureAnime.prequel,
                FixtureAnime.premiereYear,
                FixtureAnime.relatedParentStory,
                FixtureAnime.relatedFullStory,
                FixtureAnime.relatedSideStoryOva,
                FixtureAnime.relatedSideStorySpecial,
                FixtureAnime.relatedSpinOff,
                FixtureAnime.relatedAlternativeSetting,
                FixtureAnime.relatedAlternativeVersion,
                FixtureAnime.relatedSummary,
                FixtureAnime.relatedOther,
                FixtureAnime.relatedCharacter,
            ).map { it.id },
            state.groups.flatMap { group -> group.rows.map { it.id } },
        )
    }

    @Test
    fun aRowCarriesTheUsersValuesOnlyForATitleOnTheirList() {
        val rows = SubScreenFixtures.related(FixtureAnime.exactToWatch).groups.flatMap { it.rows }
        val onList = set.entries.associate { it.anime.id to it.values }
        rows.forEach { assertEquals(onList[it.id], it.entry) }
        assertEquals(setOf(FixtureAnime.prequel.id, FixtureAnime.premiereYear.id), rows.filter { it.entry != null }.map { it.id }.toSet())
    }

    @Test
    fun aRowsTotalIsCoresTotalSoAnAiringTitleWithoutMalsCountHasAniListsCount() {
        val airing = FixtureAnime.exactToWatch.copy(malEpisodes = null)
        val state =
            relatedState(
                related = listOf(RelatedAnime(airing, RelationType.Sequel)),
                entries = mapOf(airing.id to ListValues(ListStatus.Watching, 5, null, null, null)),
                airing = mapOf(airing.id to Airing(AiredCount.Exact(6), 12, null, Instant.EPOCH)),
                language = TitleLanguage.Romaji,
            )
        assertEquals(
            12,
            state.groups
                .single()
                .rows
                .single()
                .total,
        )
        val unknown = relatedState(listOf(RelatedAnime(airing, RelationType.Sequel)), emptyMap(), emptyMap(), TitleLanguage.Romaji)
        assertEquals(
            null,
            unknown.groups
                .single()
                .rows
                .single()
                .total,
        )
    }

    @Test
    fun aRowUsesTheChosenTitleLanguage() {
        val related = listOf(RelatedAnime(FixtureAnime.prequel, RelationType.Prequel))
        val english =
            relatedState(related, emptyMap(), emptyMap(), TitleLanguage.English)
                .groups
                .single()
                .rows
                .single()
        assertEquals(FixtureAnime.prequel.titles.english, english.title)
    }

    @Test
    fun eachAlternativeTitleHasItsOwnFactSoNoneIsDropped() {
        val long = "A".repeat(400)
        val anime = FixtureAnime.exactToWatch.copy(titles = FixtureAnime.exactToWatch.titles.copy(english = long))
        val facts = detailsState(anime, FixtureDetails.full, TitleLanguage.Romaji, set.clock).facts
        assertEquals(Fact.EnglishTitle(long), facts[0])
        assertEquals(Fact.JapaneseTitle("陽炎図書館 第2期"), facts[1])
        assertEquals(Fact.Synonyms(listOf("Kagetosho 2", "Heat Haze Library II")), facts[2])
    }

    @Test
    fun detailsListEveryTitleButTheMainOneOfTheChosenLanguage() {
        val titles = FixtureAnime.exactToWatch.titles
        val romaji = Fact.RomajiTitle(titles.romaji)
        val english = Fact.EnglishTitle(titles.english!!)
        val japanese = Fact.JapaneseTitle(titles.japanese!!)
        val synonyms = Fact.Synonyms(titles.synonyms)
        assertEquals(listOf(english, japanese, synonyms), alternativeTitles(TitleLanguage.Romaji))
        assertEquals(listOf(romaji, japanese, synonyms), alternativeTitles(TitleLanguage.English))
        assertEquals(listOf(romaji, english, synonyms), alternativeTitles(TitleLanguage.Japanese))
        val noEnglish = FixtureAnime.exactToWatch.copy(titles = titles.copy(english = null))
        assertEquals(listOf(japanese, synonyms), alternativeTitles(TitleLanguage.English, noEnglish))
    }

    private fun alternativeTitles(
        language: TitleLanguage,
        anime: Anime = FixtureAnime.exactToWatch,
    ): List<Fact> =
        detailsState(anime, FixtureDetails.full, language, set.clock).facts.filter {
            it is Fact.RomajiTitle || it is Fact.EnglishTitle || it is Fact.JapaneseTitle || it is Fact.Synonyms
        }

    @Test
    fun theFullFixtureShowsEveryFactInTheBudgetsOrder() {
        val facts = SubScreenFixtures.details(FixtureAnime.exactToWatch).facts
        assertEquals(
            listOf(
                Fact.EnglishTitle::class,
                Fact.JapaneseTitle::class,
                Fact.Synonyms::class,
                Fact.StartDate::class,
                Fact.EndDate::class,
                Fact.SourceFact::class,
                Fact.Studios::class,
                Fact.Rating::class,
                Fact.EpisodeLength::class,
                Fact.Broadcast::class,
            ),
            facts.map { it::class },
        )
    }

    @Test
    fun aFactMalDoesNotGiveIsLeftOut() {
        val facts = SubScreenFixtures.details(FixtureAnime.premiereUnannounced).facts
        assertEquals(listOf(Fact.JapaneseTitle("魔法少女ことね 新シリーズ"), Fact.SourceFact(FixtureDetails.sparse.source!!)), facts)
        val bare = FixtureAnime.premiereUnannounced.copy(titles = Titles("Romaji only"))
        val none = FixtureDetails.sparse.copy(source = null)
        assertEquals(emptyList<Fact>(), detailsState(bare, none, TitleLanguage.Romaji, set.clock).facts)
    }

    @Test
    fun aFinishedTitleHasNoBroadcastTime() {
        val finished = FixtureAnime.exactToWatch.copy(airingStatus = AiringStatus.Finished)
        val facts = detailsState(finished, FixtureDetails.full, TitleLanguage.Romaji, set.clock).facts
        assertEquals(emptyList<Fact>(), facts.filterIsInstance<Fact.Broadcast>())
    }

    @Test
    fun theBroadcastIsGivenInTheDevicesZone() {
        assertEquals(
            Fact.Broadcast(DayOfWeek.SATURDAY, LocalTime.of(17, 30)),
            broadcastIn(DayOfWeek.SATURDAY, LocalTime.of(23, 30), "Europe/Moscow"),
        )
        assertEquals(
            Fact.Broadcast(DayOfWeek.SATURDAY, LocalTime.of(19, 0)),
            broadcastIn(DayOfWeek.SUNDAY, LocalTime.of(1, 0), "Europe/Moscow"),
        )
        assertEquals(
            Fact.Broadcast(DayOfWeek.SATURDAY, LocalTime.of(10, 0)),
            broadcastIn(DayOfWeek.SUNDAY, LocalTime.of(0, 0), "America/New_York"),
        )
        assertEquals(Fact.Broadcast(DayOfWeek.SUNDAY, null), broadcastIn(DayOfWeek.SUNDAY, null, "Europe/Moscow"))
    }

    @Test
    fun theBroadcastTakesTheDevicesDaylightSavingOffsetWhenItNextAirs() {
        val july = Instant.parse("2026-07-15T09:00:00Z")
        assertEquals(
            Fact.Broadcast(DayOfWeek.SATURDAY, LocalTime.of(10, 30)),
            broadcastIn(DayOfWeek.SATURDAY, LocalTime.of(23, 30), "America/New_York", july),
        )
        assertEquals(
            Fact.Broadcast(DayOfWeek.SATURDAY, LocalTime.of(9, 30)),
            broadcastIn(DayOfWeek.SATURDAY, LocalTime.of(23, 30), "America/New_York"),
        )
    }

    private fun broadcastIn(
        day: DayOfWeek,
        japanTime: LocalTime?,
        zone: String,
        now: Instant = Instant.parse("2026-01-14T09:00:00Z"),
    ): Fact? {
        val anime = FixtureAnime.exactToWatch.copy(broadcast = Broadcast(day, japanTime))
        val clock = Clock.fixed(now, ZoneId.of(zone))
        return detailsState(anime, FixtureDetails.full, TitleLanguage.Romaji, clock).facts.filterIsInstance<Fact.Broadcast>().single()
    }
}

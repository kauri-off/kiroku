package io.github.kauri_off.kiroku.titlesub

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureDetails
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.model.relatedGroups
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
    fun everyRelatedAnimeIsShownInCoresGroupsAndOrder() {
        val related = FixtureDetails.full.related
        val state = SubScreenFixtures.related(FixtureAnime.exactToWatch)
        assertEquals(relatedGroups(related).map { it.first }, state.groups.map { it.relation })
        assertEquals(
            RelationType.known,
            state.groups.map { it.relation },
        )
        assertEquals(related.map { it.anime.id }.toSet(), state.groups.flatMap { g -> g.rows.map { it.id } }.toSet())
        assertEquals(related.size, state.groups.sumOf { it.rows.size })
    }

    @Test
    fun aRowCarriesTheUsersValuesOnlyForATitleOnTheirList() {
        val rows = SubScreenFixtures.related(FixtureAnime.exactToWatch).groups.flatMap { it.rows }
        val onList = set.entries.associate { it.anime.id to it.values }
        rows.forEach { assertEquals(onList[it.id], it.entry) }
        assertEquals(setOf(FixtureAnime.prequel.id, FixtureAnime.premiereYear.id), rows.filter { it.entry != null }.map { it.id }.toSet())
    }

    @Test
    fun aRowUsesTheChosenTitleLanguage() {
        val related = listOf(RelatedAnime(FixtureAnime.prequel, RelationType.Prequel))
        val english =
            relatedState(related, emptyMap(), TitleLanguage.English)
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
        val facts = detailsState(anime, FixtureDetails.full, set.clock).facts
        assertEquals(Fact.EnglishTitle(long), facts[0])
        assertEquals(Fact.JapaneseTitle("陽炎図書館 第2期"), facts[1])
        assertEquals(Fact.Synonyms(listOf("Kagetosho 2", "Heat Haze Library II")), facts[2])
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
        assertEquals(emptyList<Fact>(), detailsState(bare, none, set.clock).facts)
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

    private fun broadcastIn(
        day: DayOfWeek,
        japanTime: LocalTime?,
        zone: String,
    ): Fact? {
        val anime = FixtureAnime.exactToWatch.copy(broadcast = Broadcast(day, japanTime))
        val clock = Clock.fixed(Instant.parse("2026-01-14T09:00:00Z"), ZoneId.of(zone))
        return detailsState(anime, FixtureDetails.full, clock).facts.filterIsInstance<Fact.Broadcast>().single()
    }
}

package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import java.time.LocalDate
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime as A

object FixtureDetails {
    private class Info(
        val synopsis: String?,
        val source: Source?,
        val studios: List<String>,
        val rating: AgeRating?,
        val statistics: TitleStatistics? = null,
        val related: List<RelatedAnime> = emptyList(),
        val recommendations: List<Anime> = emptyList(),
    )

    val related: List<RelatedAnime> =
        listOf(
            RelatedAnime(A.prequel, RelationType.Prequel),
            RelatedAnime(A.premiereYear, RelationType.Sequel),
            RelatedAnime(A.relatedParentStory, RelationType.ParentStory),
            RelatedAnime(A.relatedFullStory, RelationType.FullStory),
            RelatedAnime(A.relatedSideStoryOva, RelationType.SideStory),
            RelatedAnime(A.relatedSideStorySpecial, RelationType.SideStory),
            RelatedAnime(A.relatedSpinOff, RelationType.SpinOff),
            RelatedAnime(A.relatedAlternativeSetting, RelationType.AlternativeSetting),
            RelatedAnime(A.relatedAlternativeVersion, RelationType.AlternativeVersion),
            RelatedAnime(A.relatedSummary, RelationType.Summary),
            RelatedAnime(A.relatedOther, RelationType.Other),
            RelatedAnime(A.relatedCharacter, RelationType.Character),
        )

    private val infos: Map<AnimeId, Info> =
        mapOf(
            A.exactToWatch.id to
                Info(
                    synopsis =
                        "The second year at the old prefectural library begins with a heat haze that will not " +
                            "lift. Shiori Amane, now the youngest full-time librarian, notices that the books " +
                            "returned on the hottest afternoons come back with pages that were never printed. With " +
                            "her senior colleague and a high-school volunteer who can read those pages aloud, she " +
                            "follows the stories to the people who lost them, while the library itself is scheduled " +
                            "to close at the end of the summer.",
                    source = Source.LightNovel,
                    studios = listOf("Studio Hanamizuki", "Kouyou Animation"),
                    rating = AgeRating.Teens13,
                    statistics = stats(182_345, 1_208, 8_211, 3_456, 95_432),
                    related = related,
                    recommendations = listOf(A.exactCaughtUp, A.notOnListAiring),
                ),
            A.exactCaughtUp.id to
                Info(
                    "A conservatory dropout spends the off-season in a seaside town, tuning the pianos of a hotel " +
                        "that only opens for one week a year.",
                    Source.Original,
                    listOf("Tsubame Pictures"),
                    AgeRating.Teens13,
                ),
            A.exactCaughtUpLater.id to
                Info(
                    "Above the clouds, a city of floating gardens is kept aloft by sorcerers who are never allowed " +
                        "to land. A gardener's apprentice finds one of them asleep in a greenhouse.",
                    Source.WebNovel,
                    listOf("Mizuiro Film"),
                    AgeRating.Teens13,
                ),
            A.exactOnBreak.id to
                Info(
                    "When the moon stops waning, the knights sworn to guard its light are called back from exile.",
                    Source.Manga,
                    listOf("Hoshikage Works"),
                    AgeRating.Violence17,
                ),
            A.estimatedToWatch.id to
                Info(
                    "An alchemist who can turn lead into gold would rather brew a decent cup of tea, and the " +
                        "village keeps asking for gold.",
                    Source.FourKomaManga,
                    listOf("Studio Ringo-dou"),
                    AgeRating.AllAges,
                ),
            A.estimatedCaughtUp.id to
                Info(
                    "Letters reach the valley only once the wind agrees to carry them. Its new postman has to " +
                        "learn how to ask.",
                    Source.Manga,
                    listOf("Atelier Nagi"),
                    AgeRating.AllAges,
                ),
            A.airedUnknown.id to
                Info(
                    "A detective who solves every case in a single episode has never solved the one about her " +
                        "own missing brother.",
                    Source.Manga,
                    listOf("Kouyou Animation"),
                    AgeRating.Teens13,
                ),
            A.unknownTotal.id to
                Info(
                    "Every night the continent of Hakkei redraws itself, and only the cartographers' guild can " +
                        "tell by morning where a road will lead. Sora, who remembers every place he has ever " +
                        "slept, is taken on as its youngest apprentice.\n\n" +
                        "Each region the guild charts brings new towns, new rivals and a map that refuses to stay " +
                        "finished.\n\n(Source: Studio Kumoji)",
                    Source.Manga,
                    listOf("Studio Kumoji"),
                    AgeRating.Teens13,
                ),
            A.finishedPartWay.id to
                Info(
                    "A lighthouse keeper's daughter answers the letters that wash up on her island, never " +
                        "knowing whether anyone will read the replies. One sender starts to write back.",
                    Source.VisualNovel,
                    listOf("Tsubame Pictures", "Studio Hanamizuki"),
                    AgeRating.Teens13,
                    statistics = stats(41_287, 512_906, 18_334, 22_158, 154_903),
                ),
            A.oneShortOfTotal.id to
                Info(
                    "The new junior on the ski club insists she is a snow rabbit. The club captain is not sure " +
                        "she is wrong.",
                    Source.Manga,
                    listOf("Studio Ringo-dou"),
                    AgeRating.Teens13,
                ),
            A.longTitle.id to
                Info(
                    "Reborn as a goddess of the night, she decides that the most divine thing she can do is " +
                        "serve coffee to anyone still awake.",
                    Source.LightNovel,
                    listOf("Lunaria Animation"),
                    AgeRating.Teens13,
                ),
            A.premiereTimeSoon.id to
                Info(
                    "A shopping street that sells fireworks all year round has one summer left to prove it " +
                        "should not be torn down.",
                    Source.Original,
                    listOf("Atelier Nagi"),
                    AgeRating.AllAges,
                ),
            A.premiereTimeLater.id to
                Info(
                    "Three students and a black cat investigate the small mysteries of their school.",
                    Source.Novel,
                    listOf("Studio Kumoji"),
                    AgeRating.Teens13,
                ),
            A.premiereDate.id to
                Info(
                    "Two strangers meet every spring under the same cherry tree, and only one of them remembers.",
                    Source.Manga,
                    listOf("Mizuiro Film"),
                    AgeRating.Teens13,
                ),
            A.premiereMonth.id to
                Info(
                    "A night train crosses a nebula once a year, and its passengers never board twice.",
                    Source.Original,
                    listOf("Hoshikage Works"),
                    null,
                ),
            A.premiereYear.id to
                Info(
                    "The third season of The Heat Haze Library.",
                    Source.LightNovel,
                    listOf("Studio Hanamizuki"),
                    null,
                    related = listOf(RelatedAnime(A.exactToWatch, RelationType.Prequel)),
                ),
            A.premiereUnannounced.id to
                Info(
                    synopsis = null,
                    source = Source.Original,
                    studios = emptyList(),
                    rating = null,
                    statistics = stats(0, 0, 0, 0, 1_284),
                ),
            A.plannedAiring.id to
                Info(
                    "A swordsmith's daughter forges a blade that sings, and every warlord in the province wants " +
                        "to hear it.",
                    Source.Manga,
                    listOf("Hoshikage Works"),
                    AgeRating.Violence17,
                ),
            A.plannedFinished.id to
                Info(
                    "On the last day of every summer, a prism in the school's attic shows each student a " +
                        "different year.",
                    Source.VisualNovel,
                    listOf("Lunaria Animation"),
                    AgeRating.Teens13,
                ),
            A.prequel.id to
                Info(
                    "Shiori Amane takes a summer job at a library where the heat haze makes books return with " +
                        "pages no one printed.",
                    Source.LightNovel,
                    listOf("Studio Hanamizuki"),
                    AgeRating.Teens13,
                    related = listOf(RelatedAnime(A.exactToWatch, RelationType.Sequel)),
                ),
            A.film.id to
                Info(
                    "The first train of the morning leaves a mountain station every day at 4:52. One winter it " +
                        "leaves with a passenger who bought her ticket thirty years ago and has not aged a day " +
                        "since. The young conductor on his first route has until dawn to find out where she " +
                        "is going, before the line is closed for good and the station is taken off every map.\n\n" +
                        "Along the way the train stops at towns that no longer exist, and each of them asks him " +
                        "for something in return.\n\n[Written by MAL Rewrite]",
                    Source.Original,
                    listOf("Mizuiro Film"),
                    AgeRating.Teens13,
                    statistics = stats(18_402, 2_206_917, 9_544, 6_120, 493_535),
                ),
            A.completedUnscored.id to
                Info(
                    "A witch spends one year with the cat who adopted her.",
                    Source.Manga,
                    listOf("Atelier Nagi"),
                    AgeRating.AllAges,
                ),
            A.completedOva.id to
                Info(
                    "Every girl who steps through the mirror becomes Alice, and none of them want to leave.",
                    Source.Novel,
                    listOf("Studio Kumoji"),
                    AgeRating.Violence17,
                ),
            A.completedOna.id to
                Info(
                    "Two flatmates go camping on their balcony.",
                    Source.WebManga,
                    listOf("Lantern Frame"),
                    AgeRating.AllAges,
                ),
            A.completedSpecial.id to
                Info(
                    "New Year's dreams in the seaside hotel.",
                    Source.Original,
                    listOf("Tsubame Pictures"),
                    AgeRating.Teens13,
                ),
            A.onHoldAiring.id to
                Info(
                    "A pilot whose mech is older than she is defends a frontier that her own army has abandoned.",
                    Source.Original,
                    listOf("Kouyou Animation", "Studio Kumoji"),
                    AgeRating.Violence17,
                ),
            A.onHoldFinished.id to
                Info(
                    "A boy rules a kingdom that fits in a shoebox, and its citizens have opinions.",
                    Source.PictureBook,
                    listOf("Studio Ringo-dou"),
                    AgeRating.Children,
                ),
            A.onHoldOva.id to
                Info(
                    "Two episodes about a café that only opens during storms.",
                    Source.Manga,
                    listOf("Lantern Frame"),
                    AgeRating.AllAges,
                ),
            A.droppedFinished.id to
                Info(
                    "Summoned to another realm as its hero, a line cook refuses to fight on an empty stomach.",
                    Source.LightNovel,
                    listOf("Lunaria Animation"),
                    AgeRating.Teens13,
                ),
            A.droppedEstimated.id to
                Info(
                    "Four friends have one summer before their island school closes.",
                    Source.Original,
                    listOf("Mizuiro Film"),
                    AgeRating.Teens13,
                ),
            A.droppedMovie.id to
                Info(
                    "The machines that built the old gods want them back.",
                    Source.Game,
                    listOf("Hoshikage Works"),
                    AgeRating.Violence17,
                ),
            A.notOnListAiring.id to
                Info(
                    "The Land of Light has not seen a night in three hundred years, and its knights have " +
                        "forgotten what they were sworn to guard against. When a shadow falls across the capital, " +
                        "the youngest of them is sent beyond the border with an old map, a borrowed horse and an " +
                        "order she does not understand.\n\nThe dark she finds there is not empty: it is full of " +
                        "people who were exiled from the light and who remember the war that the knights' own " +
                        "histories leave out.\n\n[Written by MAL Rewrite]",
                    Source.Manga,
                    listOf("Kouyou Animation", "Studio Kumoji", "Tsubame Pictures"),
                    AgeRating.Violence17,
                    statistics = stats(208_734, 3_917, 11_682, 9_405, 160_219),
                    recommendations = listOf(A.exactOnBreak, A.plannedAiring, A.onHoldAiring),
                ),
            A.relatedParentStory.id to
                Info("The library's first summer, before Shiori.", Source.LightNovel, listOf("Studio Hanamizuki"), AgeRating.Teens13),
            A.relatedFullStory.id to
                Info("Both seasons retold in full.", Source.LightNovel, listOf("Studio Hanamizuki"), AgeRating.Teens13),
            A.relatedSideStoryOva.id to
                Info(
                    "What the head librarian keeps in the locked room.",
                    Source.LightNovel,
                    listOf("Studio Hanamizuki"),
                    AgeRating.Teens13,
                ),
            A.relatedSideStorySpecial.id to
                Info("A summer festival at the library.", Source.LightNovel, listOf("Studio Hanamizuki"), AgeRating.Teens13),
            A.relatedSpinOff.id to
                Info("Short sketches with the library's staff.", Source.FourKomaManga, listOf("Lantern Frame"), AgeRating.AllAges),
            A.relatedAlternativeSetting.id to
                Info("The same cast, now at a high school.", Source.LightNovel, listOf("Studio Ringo-dou"), AgeRating.Teens13),
            A.relatedAlternativeVersion.id to
                Info("The first season retold as one night.", Source.LightNovel, listOf("Studio Hanamizuki"), AgeRating.Teens13),
            A.relatedSummary.id to
                Info("A recap of the first season.", Source.LightNovel, listOf("Studio Hanamizuki"), AgeRating.Teens13),
            A.relatedOther.id to
                Info("A music video for both series.", Source.Music, listOf("Tsubame Pictures"), AgeRating.AllAges),
            A.relatedCharacter.id to
                Info("Librarians among the stars.", Source.Original, listOf("Hoshikage Works"), AgeRating.AllAges),
        )

    val all: List<AnimeDetails> = A.all.map(::details)

    val full: AnimeDetails = all.single { it.id == A.exactToWatch.id }

    val sparse: AnimeDetails = all.single { it.id == A.premiereUnannounced.id }

    fun seasonOf(date: LocalDate): AnimeSeason =
        AnimeSeason(
            date.year,
            when (date.monthValue) {
                in 1..3 -> Season.Winter
                in 4..6 -> Season.Spring
                in 7..9 -> Season.Summer
                else -> Season.Fall
            },
        )

    private fun details(anime: Anime): AnimeDetails {
        val info = infos.getValue(anime.id)
        val start = (anime.startDate as? PartialDate.Day)?.date
        return AnimeDetails(
            id = anime.id,
            synopsis = info.synopsis,
            endDate = endDate(anime),
            season = start?.let(::seasonOf),
            source = info.source,
            studios = info.studios,
            rating = info.rating,
            statistics = info.statistics ?: statistics(anime),
            related = info.related,
            recommendations = info.recommendations,
        )
    }

    private fun endDate(anime: Anime): PartialDate? {
        val start = (anime.startDate as? PartialDate.Day)?.date ?: return null
        val episodes = anime.malEpisodes ?: return null
        if (anime.airingStatus == AiringStatus.NotYetAired) return null
        return PartialDate.Day(start.plusWeeks(episodes - 1L))
    }

    private fun stats(
        watching: Int,
        completed: Int,
        onHold: Int,
        dropped: Int,
        planned: Int,
    ): TitleStatistics {
        val counts = StatusCounts(watching, completed, onHold, dropped, planned)
        return TitleStatistics(counts, counts.total)
    }

    private fun statistics(anime: Anime): TitleStatistics {
        val seed = anime.id.value
        val rank = anime.rank ?: 15_000
        val members = (1_900_000.0 / (1.0 + rank / 140.0)).toInt() + (seed * 7_919 % 48_611).toInt()

        fun share(
            base: Int,
            spread: Int,
            salt: Int,
        ): Int = (members.toLong() * (base + (seed * salt % spread).toInt()) / 1_000).toInt()
        return when (anime.airingStatus) {
            AiringStatus.NotYetAired -> stats(0, 0, 0, 0, members)
            AiringStatus.Airing -> {
                val watching = share(540, 90, 37)
                val completed = share(4, 9, 11)
                val onHold = share(31, 17, 13)
                val dropped = share(38, 23, 17)
                stats(watching, completed, onHold, dropped, members - watching - completed - onHold - dropped)
            }
            AiringStatus.Finished -> {
                val watching = share(28, 19, 37)
                val completed = share(640, 110, 11)
                val onHold = share(22, 15, 13)
                val dropped = share(31, 29, 17)
                stats(watching, completed, onHold, dropped, members - watching - completed - onHold - dropped)
            }
        }
    }
}

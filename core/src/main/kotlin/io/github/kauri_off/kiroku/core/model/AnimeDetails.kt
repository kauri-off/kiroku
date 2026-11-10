package io.github.kauri_off.kiroku.core.model

import java.time.Duration

data class AnimeDetails(
    val id: AnimeId,
    val synopsis: String?,
    val genres: List<Genre>,
    val endDate: PartialDate?,
    val season: AnimeSeason?,
    val source: Source?,
    val studios: List<String>,
    val rating: AgeRating?,
    val episodeDuration: Duration?,
    val statistics: TitleStatistics?,
    val related: List<RelatedAnime>,
    val recommendations: List<Anime>,
)

data class Genre(
    val id: Int,
    val malName: String,
)

data class RelatedAnime(
    val anime: Anime,
    val relation: RelationType,
)

data class TitleStatistics(
    val statuses: StatusCounts,
    val members: Int,
)

data class StatusCounts(
    val watching: Int,
    val completed: Int,
    val onHold: Int,
    val dropped: Int,
    val planned: Int,
) {
    operator fun get(status: ListStatus): Int =
        when (status) {
            ListStatus.Watching -> watching
            ListStatus.Completed -> completed
            ListStatus.OnHold -> onHold
            ListStatus.Dropped -> dropped
            ListStatus.Planned -> planned
        }

    val total: Int get() = watching + completed + onHold + dropped + planned
}

sealed interface RelationType {
    data object Prequel : RelationType

    data object Sequel : RelationType

    data object ParentStory : RelationType

    data object FullStory : RelationType

    data object SideStory : RelationType

    data object SpinOff : RelationType

    data object AlternativeSetting : RelationType

    data object AlternativeVersion : RelationType

    data object Summary : RelationType

    data object Other : RelationType

    data object Character : RelationType

    data class Unrecognized(
        val malText: String,
    ) : RelationType

    companion object {
        val known: List<RelationType> =
            listOf(
                Prequel,
                Sequel,
                ParentStory,
                FullStory,
                SideStory,
                SpinOff,
                AlternativeSetting,
                AlternativeVersion,
                Summary,
                Other,
                Character,
            )
    }
}

sealed interface Source {
    data object Original : Source

    data object Manga : Source

    data object FourKomaManga : Source

    data object WebManga : Source

    data object Novel : Source

    data object LightNovel : Source

    data object WebNovel : Source

    data object VisualNovel : Source

    data object Game : Source

    data object Other : Source

    data class Unrecognized(
        val malText: String,
    ) : Source
}

sealed interface AgeRating {
    data object AllAges : AgeRating

    data object Children : AgeRating

    data object Teens13 : AgeRating

    data object Violence17 : AgeRating

    data object MildNudity : AgeRating

    data class Unrecognized(
        val malText: String,
    ) : AgeRating
}

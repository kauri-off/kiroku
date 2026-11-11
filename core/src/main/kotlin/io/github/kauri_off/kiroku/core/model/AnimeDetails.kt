package io.github.kauri_off.kiroku.core.model

data class AnimeDetails(
    val id: AnimeId,
    val synopsis: String?,
    val endDate: PartialDate?,
    val season: AnimeSeason?,
    val source: Source?,
    val studios: List<String>,
    val rating: AgeRating?,
    val statistics: TitleStatistics?,
    val related: List<RelatedAnime>,
    val recommendations: List<Anime>,
) {
    init {
        require(synopsis?.isNotBlank() != false)
        require(studios.all { it.isNotBlank() })
    }
}

data class RelatedAnime(
    val anime: Anime,
    val relation: RelationType,
)

data class TitleStatistics(
    val statuses: StatusCounts,
    val members: Int,
) {
    init {
        require(members >= 0)
    }
}

data class StatusCounts(
    val watching: Int,
    val completed: Int,
    val onHold: Int,
    val dropped: Int,
    val planned: Int,
) {
    init {
        require(watching >= 0 && completed >= 0 && onHold >= 0 && dropped >= 0 && planned >= 0)
    }

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
        val malCode: String,
    ) : RelationType

    companion object {
        val strip: List<RelationType> =
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
            )

        val known: List<RelationType> = strip + listOf(Other, Character)
    }
}

const val RELATED_STRIP_LIMIT = 10

fun relatedStrip(related: List<RelatedAnime>): List<RelatedAnime> =
    related
        .filter { it.relation in RelationType.strip }
        .sortedWith(relatedOrder)
        .take(RELATED_STRIP_LIMIT)

// The direct sequel to watch next; of several, the one that starts first, as that is the order they aired in.
fun sequelOf(related: List<RelatedAnime>): RelatedAnime? =
    related
        .filter { it.relation == RelationType.Sequel }
        .sortedWith(relatedOrder)
        .firstOrNull()

fun relatedGroups(related: List<RelatedAnime>): List<Pair<RelationType, List<RelatedAnime>>> =
    related
        .sortedWith(relatedOrder)
        .groupBy { it.relation }
        .toList()

private fun relationRank(relation: RelationType): Int = RelationType.known.indexOf(relation).takeIf { it >= 0 } ?: RelationType.known.size

private val relatedOrder: Comparator<RelatedAnime> =
    compareBy<RelatedAnime> { relationRank(it.relation) }
        .thenBy { (it.relation as? RelationType.Unrecognized)?.malCode.orEmpty() }
        .thenBy(nullsLast()) { it.anime.startDate?.let(::sortKey) }
        .thenBy { it.anime.titles.romaji }

private fun sortKey(date: PartialDate): String =
    when (date) {
        is PartialDate.Day -> date.date.toString()
        is PartialDate.Month -> "${date.yearMonth}-00"
        is PartialDate.OnlyYear -> "${date.value}-00-00"
    }

sealed interface Source {
    data object Original : Source

    data object Manga : Source

    data object FourKomaManga : Source

    data object WebManga : Source

    data object DigitalManga : Source

    data object Novel : Source

    data object LightNovel : Source

    data object WebNovel : Source

    data object VisualNovel : Source

    data object Game : Source

    data object CardGame : Source

    data object Book : Source

    data object PictureBook : Source

    data object Radio : Source

    data object Music : Source

    data object MixedMedia : Source

    data object Other : Source

    data class Unrecognized(
        val malCode: String,
    ) : Source
}

sealed interface AgeRating {
    data object AllAges : AgeRating

    data object Children : AgeRating

    data object Teens13 : AgeRating

    data object Violence17 : AgeRating

    data object MildNudity : AgeRating

    data object Hentai : AgeRating

    data class Unrecognized(
        val malCode: String,
    ) : AgeRating
}

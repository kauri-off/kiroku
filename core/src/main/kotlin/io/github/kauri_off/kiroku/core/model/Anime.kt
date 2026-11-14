package io.github.kauri_off.kiroku.core.model

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

@JvmInline
value class AnimeId(
    val value: Long,
)

data class Anime(
    val id: AnimeId,
    val titles: Titles,
    // MAL's two sizes of one picture: the medium one, and the large one where MAL has it.
    val posterUrl: String?,
    val largePosterUrl: String?,
    val mediaType: MediaType?,
    val airingStatus: AiringStatus,
    val malEpisodes: Int?,
    val startDate: PartialDate?,
    val broadcast: Broadcast?,
    val meanScore: Double?,
    val rank: Int?,
    val genres: List<Genre>?,
    val averageEpisodeDuration: Duration?,
) {
    init {
        require(malEpisodes == null || malEpisodes > 0)
        require(rank == null || rank > 0)
        require(meanScore == null || meanScore in 0.0..10.0)
        require(averageEpisodeDuration == null || averageEpisodeDuration > Duration.ZERO)
    }
}

data class Titles(
    val romaji: String,
    val english: String? = null,
    val japanese: String? = null,
    val synonyms: List<String> = emptyList(),
) {
    init {
        require(romaji.isNotBlank())
        require(english?.isNotBlank() != false)
        require(japanese?.isNotBlank() != false)
        require(synonyms.all { it.isNotBlank() })
    }

    fun preferred(language: TitleLanguage): String =
        when (shownLanguage(language)) {
            TitleLanguage.Romaji -> romaji
            TitleLanguage.English -> english ?: romaji
            TitleLanguage.Japanese -> japanese ?: romaji
        }

    // The language of the title shown as the main one: the chosen one, or romaji when that title is missing.
    fun shownLanguage(language: TitleLanguage): TitleLanguage =
        when (language) {
            TitleLanguage.Romaji -> TitleLanguage.Romaji
            TitleLanguage.English -> if (english == null) TitleLanguage.Romaji else language
            TitleLanguage.Japanese -> if (japanese == null) TitleLanguage.Romaji else language
        }
}

enum class TitleLanguage { Romaji, English, Japanese }

enum class AiringStatus { NotYetAired, Airing, Finished }

data class Broadcast(
    val dayOfWeek: DayOfWeek,
    val japanTime: LocalTime?,
)

data class Genre(
    val id: Int,
    val malName: String,
)

sealed interface MediaType {
    data object Tv : MediaType

    data object TvSpecial : MediaType

    data object Movie : MediaType

    data object Ova : MediaType

    data object Ona : MediaType

    data object Special : MediaType

    data object Music : MediaType

    data object Commercial : MediaType

    data object PromotionalVideo : MediaType

    data class Unrecognized(
        val malCode: String,
    ) : MediaType
}

fun malCodeAsText(malCode: String): String =
    malCode
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { it.uppercaseChar() }

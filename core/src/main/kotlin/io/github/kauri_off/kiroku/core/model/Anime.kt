package io.github.kauri_off.kiroku.core.model

import java.time.DayOfWeek
import java.time.LocalTime

@JvmInline
value class AnimeId(
    val value: Long,
)

data class Anime(
    val id: AnimeId,
    val titles: Titles,
    val posterUrl: String?,
    val mediaType: MediaType,
    val airingStatus: AiringStatus,
    val episodes: Int?,
    val startDate: PartialDate?,
    val broadcast: Broadcast?,
    val meanScore: Double?,
    val rank: Int?,
) {
    init {
        require(episodes == null || episodes > 0)
        require(rank == null || rank > 0)
    }
}

data class Titles(
    val romaji: String,
    val english: String? = null,
    val japanese: String? = null,
    val synonyms: List<String> = emptyList(),
) {
    fun preferred(language: TitleLanguage): String =
        when (language) {
            TitleLanguage.Romaji -> romaji
            TitleLanguage.English -> english ?: romaji
            TitleLanguage.Japanese -> japanese ?: romaji
        }
}

enum class TitleLanguage { Romaji, English, Japanese }

enum class AiringStatus { NotYetAired, Airing, Finished }

data class Broadcast(
    val dayOfWeek: DayOfWeek,
    val japanTime: LocalTime,
)

sealed interface MediaType {
    data object Tv : MediaType

    data object Movie : MediaType

    data object Ova : MediaType

    data object Ona : MediaType

    data object Special : MediaType

    data object Music : MediaType

    data class Unrecognized(
        val malText: String,
    ) : MediaType
}

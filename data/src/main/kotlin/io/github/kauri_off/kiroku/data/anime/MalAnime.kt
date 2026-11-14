package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.RelatedAnime
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.TitleStatistics
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.data.db.ageRatings
import io.github.kauri_off.kiroku.data.db.airingStatuses
import io.github.kauri_off.kiroku.data.db.mediaTypes
import io.github.kauri_off.kiroku.data.db.partialDateOf
import io.github.kauri_off.kiroku.data.db.relationTypes
import io.github.kauri_off.kiroku.data.db.seasons
import io.github.kauri_off.kiroku.data.db.sources
import io.github.kauri_off.kiroku.data.network.MalJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeParseException

// A title's page as MAL gives it: its summary and what only the page shows.
data class MalTitle(
    val anime: Anime,
    val details: AnimeDetails,
)

// A title as every answer of MAL's names it: a list's, a search's, a ranking's, a related title, a recommendation.
@Serializable
internal class AnimeNode(
    val id: Long,
    val title: String,
    @SerialName("main_picture") val mainPicture: Picture? = null,
    @SerialName("alternative_titles") val alternativeTitles: AlternativeTitles? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val status: String,
    @SerialName("num_episodes") val episodes: JsonElement? = null,
    @SerialName("start_date") val startDate: String? = null,
    val broadcast: MalBroadcast? = null,
    val mean: JsonElement? = null,
    val rank: JsonElement? = null,
    val genres: List<Named>? = null,
    @SerialName("average_episode_duration") val episodeSeconds: Long = 0,
)

@Serializable
internal class Picture(
    val medium: String? = null,
    val large: String? = null,
)

@Serializable
internal class AlternativeTitles(
    val synonyms: List<String> = emptyList(),
    val en: String? = null,
    val ja: String? = null,
)

@Serializable
internal class MalBroadcast(
    @SerialName("day_of_the_week") val day: String,
    @SerialName("start_time") val startTime: String? = null,
)

@Serializable
internal class Named(
    val id: Int? = null,
    val name: String? = null,
)

// What only a title's own page holds, read from the same answer as its summary.
@Serializable
private class PageNode(
    val synopsis: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("start_season") val startSeason: StartSeason? = null,
    val source: String? = null,
    val studios: List<Named> = emptyList(),
    val rating: String? = null,
    val statistics: Statistics? = null,
    @SerialName("related_anime") val related: List<JsonElement> = emptyList(),
    val recommendations: List<JsonElement> = emptyList(),
)

@Serializable
private class StartSeason(
    val year: Int? = null,
    val season: String? = null,
)

// MAL gives the five counts as strings and the member count as a number.
@Serializable
private class Statistics(
    val status: Map<String, JsonPrimitive> = emptyMap(),
    @SerialName("num_list_users") val members: JsonPrimitive? = null,
)

@Serializable
private class Related(
    val node: AnimeNode,
    @SerialName("relation_type") val relation: String,
)

@Serializable
private class Recommendation(
    val node: AnimeNode,
)

internal fun JsonObject.toTitle(): MalTitle {
    val anime = MalJson.decodeFromJsonElement(AnimeNode.serializer(), this).toAnime()
    return MalTitle(anime, MalJson.decodeFromJsonElement(PageNode.serializer(), this).toDetails(anime.id))
}

// MAL's media type for a title whose type it does not know yet.
const val NO_MEDIA_TYPE = "unknown"

// MAL's zero, empty and unreadable values are absent; a code the app does not know stays MAL's code. A node without
// an id, a title or a status cannot be mapped.
internal fun AnimeNode.toAnime(): Anime =
    Anime(
        id = AnimeId(id),
        titles =
            Titles(
                romaji = title,
                english = alternativeTitles?.en?.present(),
                japanese = alternativeTitles?.ja?.present(),
                synonyms = alternativeTitles?.synonyms.orEmpty().mapNotNull { it.present() },
            ),
        posterUrl = mainPicture?.medium?.present(),
        largePosterUrl = mainPicture?.large?.present()?.takeIf { mainPicture.medium?.present() != null },
        mediaType = mediaType?.present()?.takeIf { it != NO_MEDIA_TYPE }?.let(mediaTypes::of),
        airingStatus = airingStatuses.of(status),
        malEpisodes = episodes.number()?.intOrNull?.takeIf { it > 0 },
        startDate = startDate?.let(::dateOf),
        broadcast = broadcast?.toBroadcast(),
        meanScore = mean.number()?.doubleOrNull?.takeIf { it in 0.0..10.0 },
        rank = rank.number()?.intOrNull?.takeIf { it > 0 },
        genres = genres?.mapNotNull { genre -> genre.id?.let { id -> genre.name?.let { Genre(id, it) } } },
        averageEpisodeDuration = episodeSeconds.takeIf { it > 0 }?.let(Duration::ofSeconds),
    )

private fun PageNode.toDetails(id: AnimeId): AnimeDetails =
    AnimeDetails(
        id = id,
        synopsis = synopsis?.present(),
        endDate = endDate?.let(::dateOf),
        season = startSeason?.toSeason(),
        source = source?.present()?.let(sources::of),
        studios = studios.mapNotNull { it.name?.present() },
        rating = rating?.present()?.let(ageRatings::of),
        statistics = statistics?.toStatistics(),
        related = related.mapNotNull { mappedOrNull { it.toRelated() } },
        recommendations = recommendations.mapNotNull { mappedOrNull { it.decoded(Recommendation.serializer()).node.toAnime() } },
    )

private fun JsonElement.toRelated(): RelatedAnime {
    val related = decoded(Related.serializer())
    return RelatedAnime(related.node.toAnime(), relationTypes.of(related.relation))
}

// A related title or a recommendation that cannot be mapped is left out; the page shows without it.
private inline fun <T> mappedOrNull(map: () -> T): T? =
    try {
        map()
    } catch (_: IllegalArgumentException) {
        null
    }

private fun <T> JsonElement.decoded(serializer: KSerializer<T>): T = MalJson.decodeFromJsonElement(serializer, this)

// A season whose code the app does not know is no season.
private fun StartSeason.toSeason(): AnimeSeason? {
    val season = season?.let(seasons.values::get) ?: return null
    return AnimeSeason(year ?: return null, season)
}

private fun dateOf(text: String): PartialDate? =
    try {
        text.present()?.let(::partialDateOf)
    } catch (_: DateTimeParseException) {
        null
    }

private fun JsonElement?.number(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it.isString }

// A day MAL names that is not a weekday leaves the title without a broadcast.
private fun MalBroadcast.toBroadcast(): Broadcast? {
    val weekday = DayOfWeek.entries.find { it.name.equals(day, ignoreCase = true) } ?: return null
    return Broadcast(weekday, startTime?.present()?.let(::timeOf))
}

// A time that does not parse is no time.
private fun timeOf(text: String): LocalTime? =
    try {
        LocalTime.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

// Statistics with a count missing or unreadable are no statistics.
private fun Statistics.toStatistics(): TitleStatistics? {
    fun count(key: String): Int? = status[key]?.content?.toIntOrNull()?.takeIf { it >= 0 }
    return TitleStatistics(
        StatusCounts(
            watching = count("watching") ?: return null,
            completed = count("completed") ?: return null,
            onHold = count("on_hold") ?: return null,
            dropped = count("dropped") ?: return null,
            planned = count("plan_to_watch") ?: return null,
        ),
        members?.content?.toIntOrNull()?.takeIf { it >= 0 } ?: return null,
    )
}

private fun String.present(): String? = takeIf { it.isNotBlank() }

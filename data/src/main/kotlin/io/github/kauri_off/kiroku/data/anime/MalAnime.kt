package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.Genre
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

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
    @SerialName("num_episodes") val episodes: Int = 0,
    @SerialName("start_date") val startDate: String? = null,
    val broadcast: MalBroadcast? = null,
    val mean: Double? = null,
    val rank: Int? = null,
    val genres: List<Named>? = null,
    @SerialName("average_episode_duration") val episodeSeconds: Long = 0,
)

@Serializable
internal class Picture(
    val medium: String,
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
    val id: Int,
    val name: String,
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
    @SerialName("related_anime") val related: List<Related> = emptyList(),
    val recommendations: List<Recommendation> = emptyList(),
)

@Serializable
private class StartSeason(
    val year: Int,
    val season: String,
)

// MAL gives the five counts as strings and the member count as a number.
@Serializable
private class Statistics(
    val status: Map<String, JsonPrimitive>,
    @SerialName("num_list_users") val members: Int,
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

// MAL's zero and empty values are absent; a code the app does not know stays MAL's code.
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
        posterUrl = mainPicture?.medium,
        largePosterUrl = mainPicture?.large,
        mediaType = mediaType?.present()?.let(mediaTypes::of),
        airingStatus = airingStatuses.of(status),
        malEpisodes = episodes.takeIf { it > 0 },
        startDate = startDate?.present()?.let(::partialDateOf),
        broadcast = broadcast?.toBroadcast(),
        meanScore = mean,
        rank = rank,
        genres = genres?.map { Genre(it.id, it.name) },
        averageEpisodeDuration = episodeSeconds.takeIf { it > 0 }?.let(Duration::ofSeconds),
    )

private fun PageNode.toDetails(id: AnimeId): AnimeDetails =
    AnimeDetails(
        id = id,
        synopsis = synopsis?.present(),
        endDate = endDate?.present()?.let(::partialDateOf),
        season = startSeason?.let { AnimeSeason(it.year, seasons.of(it.season)) },
        source = source?.present()?.let(sources::of),
        studios = studios.mapNotNull { it.name.present() },
        rating = rating?.present()?.let(ageRatings::of),
        statistics = statistics?.toStatistics(),
        related = related.map { RelatedAnime(it.node.toAnime(), relationTypes.of(it.relation)) },
        recommendations = recommendations.map { it.node.toAnime() },
    )

// A day MAL names that is not a weekday leaves the title without a broadcast.
private fun MalBroadcast.toBroadcast(): Broadcast? {
    val weekday = DayOfWeek.entries.find { it.name.equals(day, ignoreCase = true) } ?: return null
    return Broadcast(weekday, startTime?.present()?.let(LocalTime::parse))
}

private fun Statistics.toStatistics(): TitleStatistics {
    fun count(key: String): Int = status.getValue(key).content.toInt()
    return TitleStatistics(
        StatusCounts(
            watching = count("watching"),
            completed = count("completed"),
            onHold = count("on_hold"),
            dropped = count("dropped"),
            planned = count("plan_to_watch"),
        ),
        members,
    )
}

private fun String.present(): String? = takeIf { it.isNotBlank() }

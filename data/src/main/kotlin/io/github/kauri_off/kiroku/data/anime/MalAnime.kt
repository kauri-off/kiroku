package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.model.AiringStatus
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
import io.github.kauri_off.kiroku.data.db.Unread
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeParseException

// A title's page as MAL gives it: its summary and what only the page shows.
data class MalTitle(
    val anime: Anime,
    val details: AnimeDetails,
    // What of it the mapper could not read whole, so that a stored value stands.
    val unread: Set<Unread> = emptySet(),
)

// A title as every answer of MAL's names it: a list's, a search's, a ranking's, a related title, a recommendation.
@Serializable
internal class AnimeNode(
    val id: JsonElement? = null,
    val title: String? = null,
    @SerialName("main_picture") val mainPicture: Picture? = null,
    @SerialName("alternative_titles") val alternativeTitles: AlternativeTitles? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val status: String? = null,
    @SerialName("num_episodes") val episodes: JsonElement? = null,
    @SerialName("start_date") val startDate: String? = null,
    val broadcast: MalBroadcast? = null,
    val mean: JsonElement? = null,
    val rank: JsonElement? = null,
    val genres: List<Named>? = null,
    @SerialName("average_episode_duration") val episodeSeconds: JsonElement? = null,
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
    @SerialName("day_of_the_week") val day: String? = null,
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

internal fun JsonObject.toTitle(repairs: Repairs): MalTitle {
    val anime = MalJson.decodeFromJsonElement(AnimeNode.serializer(), this).toAnime(repairs)
    return MalTitle(anime, MalJson.decodeFromJsonElement(PageNode.serializer(), this).toDetails(anime.id, repairs))
}

// MAL's fields that held a value the mapper could not read and left out or filled in; a related title's under its list's name.
internal class Repairs private constructor(
    private val prefix: String,
    private val noted: MutableSet<String>,
) {
    constructor() : this("", sortedSetOf())

    val fields: Set<String> get() = noted

    fun note(field: String) {
        noted += prefix + field
    }

    fun within(list: String) = Repairs("$prefix$list.", noted)

    // The values of the page itself it could only read in part.
    val unread: Set<Unread> get() = Unread.entries.filterTo(mutableSetOf()) { unreadFields.getValue(it) in noted }

    // [read] of a value MAL gave; noted when it gave one that reads as nothing.
    fun <T : Any> read(
        field: String,
        given: Boolean,
        read: T?,
    ): T? = read.also { if (it == null && given) note(field) }
}

// MAL's media type for a title whose type it does not know yet.
const val NO_MEDIA_TYPE = "unknown"

// MAL's status for a title it gives none.
private val NO_STATUS = AiringStatus.Unrecognized("")

// MAL's broadcast day of a title that airs on no one weekday.
private const val NO_WEEKDAY = "other"

private val unreadFields =
    mapOf(
        Unread.BroadcastTime to "broadcast.start_time",
        Unread.Genres to "genres",
        Unread.Studios to "studios",
        Unread.Related to "related_anime",
        Unread.Recommendations to "recommendations",
    )

// MAL's own empty values are absent; an unreadable value is left out or filled in, and noted in [repairs].
internal fun AnimeNode.toAnime(repairs: Repairs): Anime {
    val picture = mainPicture?.medium?.present()
    val genresGiven = genres?.mapNotNull { genre -> genre.id?.let { id -> genre.name?.present()?.let { Genre(id, it) } } }
    if (genresGiven != null && genresGiven.size < genres.size) repairs.note("genres")
    return Anime(
        id = AnimeId(requireNotNull(id.positiveLong())),
        titles = titles(repairs),
        posterUrl = repairs.read("main_picture", mainPicture != null, picture),
        largePosterUrl = mainPicture?.large?.present()?.takeIf { picture != null },
        mediaType = mediaType?.present()?.takeIf { it != NO_MEDIA_TYPE }?.let(mediaTypes::of),
        airingStatus = repairs.read("status", true, status?.present()?.let(airingStatuses::of)) ?: NO_STATUS,
        malEpisodes = repairs.read("num_episodes", episodes.given(), episodes.number()?.intOrNull?.takeIf { it >= 0 })?.takeIf { it > 0 },
        startDate = repairs.read("start_date", startDate.given(), startDate?.let(::dateOf)),
        broadcast = broadcast?.toBroadcast(repairs),
        meanScore = repairs.read("mean", mean.given(), mean.number()?.doubleOrNull?.takeIf { it in 0.0..10.0 }),
        rank = repairs.read("rank", rank.given(), rank.number()?.intOrNull?.takeIf { it > 0 }),
        genres = genresGiven,
        averageEpisodeDuration =
            repairs
                .read("average_episode_duration", episodeSeconds.given(), episodeSeconds.number()?.longOrNull?.takeIf { it >= 0 })
                ?.takeIf { it > 0 }
                ?.let(Duration::ofSeconds),
    )
}

// Without MAL's main title the English one takes its place, then the Japanese one (section 10's order).
private fun AnimeNode.titles(repairs: Repairs): Titles {
    val english = alternativeTitles?.en?.present()
    val japanese = alternativeTitles?.ja?.present()
    val synonyms = alternativeTitles?.synonyms.orEmpty().mapNotNull { it.present() }
    title?.present()?.let { return Titles(it, english, japanese, synonyms) }
    repairs.note("title")
    english?.let { return Titles(it, null, japanese, synonyms) }
    return Titles(requireNotNull(japanese), null, null, synonyms)
}

private fun PageNode.toDetails(
    id: AnimeId,
    repairs: Repairs,
): AnimeDetails {
    val named = studios.mapNotNull { it.name?.present() }
    if (named.size < studios.size) repairs.note("studios")
    return AnimeDetails(
        id = id,
        synopsis = synopsis?.present(),
        endDate = repairs.read("end_date", endDate.given(), endDate?.let(::dateOf)),
        season = repairs.read("start_season", startSeason != null, startSeason?.toSeason()),
        source = source?.present()?.let(sources::of),
        studios = named,
        rating = rating?.present()?.let(ageRatings::of),
        statistics = repairs.read("statistics", statistics != null, statistics?.toStatistics()),
        related = related.mappedOrLeftOut("related_anime", repairs) { it.toRelated(repairs.within("related_anime")) },
        recommendations =
            recommendations.mappedOrLeftOut("recommendations", repairs) {
                it.decoded(Recommendation.serializer()).node.toAnime(repairs.within("recommendations"))
            },
    )
}

private fun JsonElement.toRelated(repairs: Repairs): RelatedAnime {
    val related = decoded(Related.serializer())
    return RelatedAnime(related.node.toAnime(repairs), relationTypes.of(related.relation))
}

// A related title or a recommendation that cannot be mapped is left out, and noted; the page shows without it.
private inline fun <T> List<JsonElement>.mappedOrLeftOut(
    list: String,
    repairs: Repairs,
    map: (JsonElement) -> T,
): List<T> =
    mapNotNull {
        try {
            map(it)
        } catch (_: IllegalArgumentException) {
            repairs.note(list)
            null
        }
    }

private fun <T> JsonElement.decoded(serializer: KSerializer<T>): T = MalJson.decodeFromJsonElement(serializer, this)

// A season whose code the app does not know is no season.
private fun StartSeason.toSeason(): AnimeSeason? {
    val season = season?.let(seasons.values::get) ?: return null
    return AnimeSeason(year ?: return null, season)
}

internal fun dateOf(text: String): PartialDate? =
    try {
        text.present()?.let(::partialDateOf)
    } catch (_: DateTimeParseException) {
        null
    }

// A number MAL gives in quotes is read as the number, as every numeric field is.
private fun JsonElement?.number(): JsonPrimitive? = this as? JsonPrimitive

private fun JsonElement?.positiveLong(): Long? = number()?.longOrNull?.takeIf { it > 0 }

// MAL gave a value here: not left out, not null, not empty.
private fun JsonElement?.given(): Boolean = this != null && this !is JsonNull && !(this is JsonPrimitive && isString && content.isBlank())

private fun String?.given(): Boolean = this?.present() != null

// A broadcast without a weekday, or with a day that is not one, is no broadcast; only MAL's own "other" or none is not noted.
private fun MalBroadcast.toBroadcast(repairs: Repairs): Broadcast? {
    val given = day.given() && !day.equals(NO_WEEKDAY, ignoreCase = true)
    val weekday = repairs.read("broadcast", given, DayOfWeek.entries.find { it.name.equals(day, ignoreCase = true) }) ?: return null
    return Broadcast(weekday, repairs.read("broadcast.start_time", startTime.given(), startTime?.present()?.let(::timeOf)))
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

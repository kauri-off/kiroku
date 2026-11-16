package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant

// One AniList entry as one page of its schedule shows it.
internal data class AniListEntry(
    val malId: Int,
    val total: Int?,
    val status: String?,
    val episodes: List<ScheduledEpisode>,
    val moreEpisodes: Boolean,
)

// One page of titles: each entry read on its own, the MAL ids of entries that could not be read, and whether more follow.
internal class AniListPage(
    val entries: List<AniListEntry>,
    val unreadable: Set<Int>,
    val moreTitles: Boolean,
)

// What of an answer of AniList's could not be read, never quoting a value.
class AniListOddAnswer(
    what: String,
) : Exception(what)

// The page an answer holds; null when it is none. [report] hears of what could not be read, unless it is not JSON at all.
internal fun aniListPage(
    body: String,
    report: (AniListOddAnswer) -> Unit,
): AniListPage? {
    val answer =
        try {
            AniListJson.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return null
        }
    val page =
        (answer as? JsonObject)
            ?.takeIf { "errors" !in it }
            ?.decodedOrNull(Answer.serializer())
            ?.data
            ?.page
    if (page == null) {
        report(AniListOddAnswer("page"))
        return null
    }
    val entries = mutableListOf<AniListEntry>()
    val unreadable = mutableSetOf<Int>()
    for (media in page.media) {
        val entry = media.entry()
        if (entry != null) {
            entries += entry
            continue
        }
        val malId = ((media as? JsonObject)?.get("idMal") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        report(AniListOddAnswer(if (malId == null) "idMal" else "media"))
        unreadable += malId ?: return null
    }
    return AniListPage(entries, unreadable, page.pageInfo.hasNextPage)
}

private fun JsonElement.entry(): AniListEntry? {
    val media = decodedOrNull(Media.serializer()) ?: return null
    return AniListEntry(
        malId = media.idMal,
        total = media.episodes?.takeIf { it > 0 },
        status = media.status,
        episodes =
            media.airingSchedule.nodes
                .filter { it.episode > 0 }
                .map { ScheduledEpisode(it.episode, Instant.ofEpochSecond(it.airingAt.takeIf { at -> at in Readable } ?: return null)) },
        moreEpisodes = media.airingSchedule.pageInfo.hasNextPage,
    )
}

private fun <T> JsonElement.decodedOrNull(serializer: KSerializer<T>): T? =
    try {
        AniListJson.decodeFromJsonElement(serializer, this)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

// Air times a schedule can hold: from 1900 to 2200.
private val Readable = -2_208_988_800L..7_258_118_400L

private val AniListJson = Json { ignoreUnknownKeys = true }

@Serializable
private class Answer(
    val data: Data? = null,
)

@Serializable
private class Data(
    @SerialName("Page") val page: Page,
)

@Serializable
private class Page(
    val pageInfo: PageInfo,
    val media: List<JsonElement>,
)

@Serializable
private class PageInfo(
    val hasNextPage: Boolean,
)

@Serializable
private class Media(
    val idMal: Int,
    val episodes: Int? = null,
    val status: String? = null,
    val airingSchedule: Schedule,
)

@Serializable
private class Schedule(
    val pageInfo: PageInfo,
    val nodes: List<Node>,
)

@Serializable
private class Node(
    val episode: Int,
    val airingAt: Long,
)

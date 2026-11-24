package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.data.anime.AnimeNode
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.dateOf
import io.github.kauri_off.kiroku.data.anime.given
import io.github.kauri_off.kiroku.data.anime.toAnime
import io.github.kauri_off.kiroku.data.db.listStatuses
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.nextOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// One page of a user's list: its entries, the offset of the next page, which the last page has none of, and its repairs.
internal class ListPage(
    val entries: List<ListEntry>,
    val next: Int?,
    val repaired: Set<String>,
)

@Serializable
private class PageNode(
    val data: List<ItemNode>,
    val paging: JsonElement? = null,
)

@Serializable
private class ItemNode(
    val node: AnimeNode,
    @SerialName("list_status") val values: JsonElement? = null,
)

// An entry whose last change MAL does not give sorts as the oldest.
internal val NEVER_UPDATED: Instant = Instant.EPOCH

// Every entry is mapped or the page fails, as does a page without its entries: none may leave the list.
internal fun JsonObject.toListPage(repairs: Repairs): ListPage {
    val page = MalJson.decodeFromJsonElement(PageNode.serializer(), this)
    val entries = page.data.map { listEntryOf(it.node, it.values, repairs) }
    return ListPage(entries, nextOffset(page.paging), repairs.fields.toSet())
}

// A score of 0, or any value missing or unreadable, is absent, an unreadable one noted; only a missing list status fails.
private fun listEntryOf(
    node: AnimeNode,
    values: JsonElement?,
    repairs: Repairs,
): ListEntry = listEntryOf(node.toAnime(repairs.within("node")), values, repairs)

// An entry's values as MAL gives them, in a list, a title's own answer or a write's, with the title they belong to.
internal fun listEntryOf(
    anime: Anime,
    values: JsonElement?,
    repairs: Repairs,
): ListEntry {
    val fields = values as? JsonObject ?: JsonObject(emptyMap())
    val noted = repairs.within("list_status")

    fun <T : Any> read(
        key: String,
        value: T?,
    ): T? = noted.read(key, fields[key].given(), value)
    return ListEntry(
        anime,
        ListValues(
            status = statusOf(fields.text("status"), noted),
            watchedEpisodes = read("num_episodes_watched", fields.number("num_episodes_watched")?.takeIf { it >= 0 }) ?: 0,
            score = read("score", fields.number("score")?.takeIf { it in 0..10 })?.takeIf { it > 0 },
            startDate = read("start_date", fields.text("start_date")?.let(::dateOf)),
            finishDate = read("finish_date", fields.text("finish_date")?.let(::dateOf)),
            timesRewatched = read("num_times_rewatched", fields.number("num_times_rewatched")?.takeIf { it >= 0 }) ?: 0,
        ),
        read("updated_at", fields.text("updated_at")?.let(::updatedAtOf)) ?: NEVER_UPDATED,
    )
}

// A status the app does not know is kept as MAL's code and noted, so that the entry stays on the list.
private fun statusOf(
    code: String?,
    noted: Repairs,
): EntryStatus {
    requireNotNull(code) { "No list status" }
    return listStatuses.of(code).also { if (it is EntryStatus.Unrecognized) noted.note("status") }
}

private fun JsonObject.text(key: String): String? =
    (get(key) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotBlank() }

// A number MAL gives in quotes is read as the number.
private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

private fun updatedAtOf(text: String): Instant? =
    try {
        OffsetDateTime.parse(text).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

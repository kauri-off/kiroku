package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.data.anime.AnimeNode
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.dateOf
import io.github.kauri_off.kiroku.data.anime.given
import io.github.kauri_off.kiroku.data.anime.toAnime
import io.github.kauri_off.kiroku.data.db.listStatuses
import io.github.kauri_off.kiroku.data.network.MalJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    return ListPage(entries, nextOf(page.paging), repairs.fields.toSet())
}

// A score of 0, or any value missing or unreadable, is absent, an unreadable one noted; only an unknown list status fails.
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
            status = requireNotNull(fields.text("status")?.let(listStatuses.values::get)) { "No list status the app knows" },
            watchedEpisodes = read("num_episodes_watched", fields.number("num_episodes_watched")?.takeIf { it >= 0 }) ?: 0,
            score = read("score", fields.number("score")?.takeIf { it in 0..10 })?.takeIf { it > 0 },
            startDate = read("start_date", fields.text("start_date")?.let(::dateOf)),
            finishDate = read("finish_date", fields.text("finish_date")?.let(::dateOf)),
        ),
        read("updated_at", fields.text("updated_at")?.let(::updatedAtOf)) ?: NEVER_UPDATED,
    )
}

// No next link is the last page; of a link only its offset is used, and one without an offset fails.
private fun nextOf(paging: JsonElement?): Int? {
    if (paging == null || paging is JsonNull) return null
    check(paging is JsonObject) { "Paging is not an object" }
    val next = paging["next"]
    if (next == null || next is JsonNull) return null
    val url = (next as? JsonPrimitive)?.takeIf { it.isString }?.content?.toHttpUrlOrNull()
    return checkNotNull(url?.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 }) { "No next offset" }
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

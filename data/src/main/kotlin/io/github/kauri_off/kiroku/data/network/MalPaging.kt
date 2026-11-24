package io.github.kauri_off.kiroku.data.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun nextOffset(paging: JsonElement?): Int? {
    if (paging == null || paging is JsonNull) return null
    check(paging is JsonObject) { "Paging is not an object" }
    val next = paging["next"]
    if (next == null || next is JsonNull) return null
    val url = (next as? JsonPrimitive)?.takeIf { it.isString }?.content?.toHttpUrlOrNull()
    return checkNotNull(url?.queryParameter("offset")?.toIntOrNull()?.takeIf { it >= 0 }) { "No next offset" }
}

package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.model.TitlePicture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun JsonElement?.titlePictures(
    cover: TitlePicture,
    onUnread: () -> Unit,
): List<TitlePicture> {
    if (this == null || this == JsonNull) return emptyList()
    if (this !is JsonArray) {
        onUnread()
        return emptyList()
    }

    val seen = cover.sources().mapTo(mutableSetOf(), ::pictureKey)
    return mapNotNull { value ->
        val node = value as? JsonObject
        val picture = TitlePicture(node?.get("medium").pictureUrl(onUnread), node?.get("large").pictureUrl(onUnread))
        val keys = picture.sources().map(::pictureKey)
        when {
            keys.isEmpty() -> {
                onUnread()
                null
            }
            keys.any { it in seen } -> null
            else -> picture.also { seen.addAll(keys) }
        }
    }
}

private fun JsonElement?.pictureUrl(onUnread: () -> Unit): String? {
    if (this == null || this == JsonNull) return null
    val url = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    return url?.takeIf { it.toHttpUrlOrNull()?.isHttps == true }.also { if (it == null) onUnread() }
}

private val malCoverVariant = Regex("""(https://cdn\.myanimelist\.net/images/(?:anime|manga)/[0-9]+/[0-9]+l?)\.(?:jpg|webp)""")

private fun pictureKey(url: String): String = malCoverVariant.matchEntire(url)?.let { "${it.groupValues[1]}.jpg" } ?: url

package io.github.kauri_off.kiroku.core.link

import io.github.kauri_off.kiroku.core.model.AnimeId
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

// MAL's hosts the app takes a title's link from; the manifest claims these and no other.
val animeLinkHosts: List<String> = listOf("myanimelist.net", "www.myanimelist.net")

// A title's page: its id, then at most its name, then at most a slash. Sub-pages show what the title page does not.
private val animePage = Regex("/anime/([1-9][0-9]*)(/[^/]+)?/?")

// The title a `https` link to its page on MAL names, or null for any other address.
fun animeLink(address: String): AnimeId? {
    val uri =
        try {
            URI(address)
        } catch (_: URISyntaxException) {
            return null
        }
    val host = uri.host?.lowercase(Locale.ROOT)
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port != -1 || host !in animeLinkHosts) {
        return null
    }
    val id = animePage.matchEntire(uri.path ?: return null)?.groupValues?.get(1) ?: return null
    return id.toLongOrNull()?.let(::AnimeId)
}

package io.github.kauri_off.kiroku.core.link

import io.github.kauri_off.kiroku.core.model.AnimeId
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

// What a link to a page of MAL's site opens in the app.
sealed interface Link {
    data class Title(
        val id: AnimeId,
    ) : Link

    // The user as the link spells them, decoded; whether two names are one user is not the link's to say.
    data class Profile(
        val name: String,
    ) : Link
}

// MAL's hosts the app takes a link from; the manifest claims these and no other.
val linkHosts: List<String> = listOf("myanimelist.net", "www.myanimelist.net")

// A title's page: its id, then at most its name, then at most a slash. Sub-pages show what the title page does not.
private val animePage = Regex("/anime/([1-9][0-9]*)(/[^/]+)?/?")

// A user's profile page: one segment, then at most a slash.
private val profilePage = Regex("/profile/([^/]+)/?")

// What a `https` link to a title's or a user's page on MAL opens, or null for any other address.
fun readLink(address: String): Link? {
    val uri =
        try {
            URI(address)
        } catch (_: URISyntaxException) {
            return null
        }
    val host = uri.host?.lowercase(Locale.ROOT)
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null || uri.port != -1 || host !in linkHosts) {
        return null
    }
    val path = uri.rawPath ?: return null
    animePage.matchEntire(path)?.let { page ->
        // As written and as decoded: an encoded slash is no part of the page's address.
        if (!animePage.matches(uri.path)) return null
        return page.groupValues[1].toLongOrNull()?.let { Link.Title(AnimeId(it)) }
    }
    val name =
        profilePage
            .matchEntire(path)
            ?.groupValues
            ?.get(1)
            ?.let(::decoded) ?: return null
    return if (name.isBlank() || '/' in name || name == "." || name == "..") null else Link.Profile(name)
}

// The same reader over an address the person typed or pasted, which may lack its scheme.
fun readTypedLink(text: String): Link? = readLink(if ("://" in text) text else "https://$text")

private val escape = Regex("%([0-9A-Fa-f]{2})")

// A path segment with its escapes decoded as UTF-8; null when the bytes are not UTF-8.
private fun decoded(segment: String): String? {
    val bytes = ByteArrayOutputStream()
    var at = 0
    for (match in escape.findAll(segment)) {
        bytes.write(segment.substring(at, match.range.first).toByteArray())
        bytes.write(match.groupValues[1].toInt(16))
        at = match.range.last + 1
    }
    bytes.write(segment.substring(at).toByteArray())
    return try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

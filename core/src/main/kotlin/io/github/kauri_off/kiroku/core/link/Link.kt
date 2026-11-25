package io.github.kauri_off.kiroku.core.link

import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.MangaId
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

// What a link to a page of MAL's site opens in the app.
sealed interface Link {
    data class Title(
        val id: AnimeId,
    ) : Link

    data class MangaTitle(
        val id: MangaId,
    ) : Link

    // The user as the link spells them, decoded; only a name `isAcceptedUserName` takes. Whether two names are one
    // user is not the link's to say.
    data class Profile(
        val name: String,
    ) : Link
}

// MAL's hosts the app takes a link from; the manifest claims these and no other.
val linkHosts: List<String> = listOf("myanimelist.net", "www.myanimelist.net")

// The longest user name the app takes, chosen generously.
const val USER_NAME_MAX_LENGTH = 64

// The app's safety rule for a name from another app or from typing, not MAL's rule for names.
private val acceptedUserName = Regex("[A-Za-z0-9_-]{1,$USER_NAME_MAX_LENGTH}")

// ASCII letters, digits, `_` and `-`, at most [USER_NAME_MAX_LENGTH] of them.
fun isAcceptedUserName(name: String): Boolean = acceptedUserName.matches(name)

// A title's page: its id, then at most its name, then at most a slash. Sub-pages show what the title page does not.
private val titlePage = Regex("/(anime|manga)/([1-9][0-9]*)(/[^/]+)?/?")

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
    titlePage.matchEntire(path)?.let { page ->
        if (!titlePage.matches(uri.path)) return null
        val id = page.groupValues[2].toLongOrNull() ?: return null
        return if (page.groupValues[1] == "manga") Link.MangaTitle(MangaId(id)) else Link.Title(AnimeId(id))
    }
    val name =
        profilePage
            .matchEntire(path)
            ?.groupValues
            ?.get(1)
            ?.let(::decoded) ?: return null
    return if (isAcceptedUserName(name)) Link.Profile(name) else null
}

// The same reader over an address the person typed or pasted, which may lack its scheme or have `http`.
fun readTypedLink(text: String): Link? {
    val address =
        when {
            "://" !in text -> "https://$text"
            text.substringBefore("://").equals("http", ignoreCase = true) -> "https://" + text.substringAfter("://")
            else -> text
        }
    return readLink(address)
}

private val escape = Regex("%([0-9A-Fa-f]{2})")

// Each escape as the character of its byte; a byte above ASCII then fails the name rule.
private fun decoded(segment: String): String =
    escape.replace(segment) {
        it.groupValues[1]
            .toInt(16)
            .toChar()
            .toString()
    }

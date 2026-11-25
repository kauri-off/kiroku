package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.link.linkHosts
import io.github.kauri_off.kiroku.core.link.readTypedLink
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.notShown

internal fun animeSearchLink(text: String): AnimeId? {
    val path = text.substringBefore('?').substringBefore('#')
    if ('/' !in path) return null
    val host = linkHosts.first()
    val address =
        when {
            text.startsWith(notShown("https://"), ignoreCase = true) || text.startsWith(notShown("http://"), ignoreCase = true) -> text
            text.startsWith(notShown("//")) -> notShown("https:$text")
            text.startsWith(notShown("/anime/")) -> notShown("https://$host$text")
            text.startsWith(notShown("anime/")) -> notShown("https://$host/$text")
            slugSuffix.matches(path) -> notShown("https://$host/anime/$text")
            else -> notShown("https://$text")
        }
    return (readTypedLink(address) as? Link.Title)?.id
}

private val slugSuffix = Regex(notShown("[1-9][0-9]*/[A-Za-z0-9-]+_[A-Za-z0-9_-]+/?"))

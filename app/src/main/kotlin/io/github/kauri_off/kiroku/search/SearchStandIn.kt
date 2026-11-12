package io.github.kauri_off.kiroku.search

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.repository.BrowseList

// What Search shows until its data layer exists: fixture titles in builds that carry them, nothing in a release.
interface SearchStandIn {
    val recent: List<String>

    fun search(query: String): List<SearchHit>

    fun browse(list: BrowseList): List<SearchHit>
}

data class SearchHit(
    val anime: Anime,
    val entry: ListEntry?,
    val rank: Int? = null,
)

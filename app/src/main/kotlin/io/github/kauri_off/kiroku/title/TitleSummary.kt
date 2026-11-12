package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session

// What a row or a page shows of one title, handed to the screen it opens, which starts from it and falls back to it.
class TitleSummary(
    val anime: Anime,
    // Null when the opener's list holds no entry for the title.
    val entry: ListEntry?,
    val airing: Airing?,
    val session: Session?,
    // Only when the opener is the title's own page.
    val page: PageSummary? = null,
)

// What a title page shows below its header: the details, and the list entries and airing of the related titles.
class PageSummary(
    val details: AnimeDetails,
    val related: Map<AnimeId, ListEntry>,
    val relatedAiring: Map<AnimeId, Airing>,
)

// The list entries and airing the summary knows, for a screen's first frame.
internal fun TitleSummary.knownEntries(): Map<AnimeId, ListEntry> =
    page?.related.orEmpty() + listOfNotNull(entry).associateBy { it.anime.id }

internal fun TitleSummary.knownAiring(): Map<AnimeId, Airing> =
    page?.relatedAiring.orEmpty() + listOfNotNull(airing?.let { anime.id to it })

// A page's summary, with what the page knows of the titles related to it.
fun pageSummary(
    details: AnimeDetails,
    entries: Map<AnimeId, ListEntry>,
    airing: Map<AnimeId, Airing>,
): PageSummary {
    val ids = details.related.map { it.anime.id }.toSet()
    return PageSummary(details, entries.filterKeys(ids::contains), airing.filterKeys(ids::contains))
}

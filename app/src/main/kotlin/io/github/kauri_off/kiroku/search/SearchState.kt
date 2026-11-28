package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.text.input.TextFieldState
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.RecentTitleId

data class SearchState(
    // The search field's text, owned by the caller and read by the field itself, so no keystroke waits (ARCHITECTURE 6.2).
    val query: TextFieldState,
    // Whether the full-screen search view is open when the screen is first composed; it then follows the user.
    val expanded: Boolean,
    val content: SearchContent,
    val sessionExpired: Boolean = false,
    // Whether a result's long press opens its edit sheet: only while there is a list to edit.
    val editable: Boolean = false,
)

sealed interface SearchContent {
    // Before a query: the recent searches, newest first, then the browse lists.
    data class Start(
        val recent: List<String>,
        val suggestions: Boolean = false,
        val mangaAvailable: Boolean = false,
        val viewed: List<RecentTitleRow> = emptyList(),
    ) : SearchContent {
        init {
            require(recent.size <= MAX_RECENT_SEARCHES)
        }
    }

    // The results of [query], which the field's text may already have moved on from.
    data class Results(
        val query: String,
        val results: Paged<ResultRow>,
    ) : SearchContent {
        // The MAL id the query names when it is digits alone.
        val openId: AnimeId? get() = queriedId(query)
    }

    data class AnimeLink(
        val id: AnimeId,
    ) : SearchContent

    data class MangaStart(
        val recent: List<String>,
        val viewed: List<RecentTitleRow> = emptyList(),
    ) : SearchContent

    data class MangaResults(
        val query: String,
        val results: Paged<MangaResultRow>,
    ) : SearchContent
}

data class RecentTitleRow(
    val key: RecentTitleId,
    val title: String,
)

const val MAX_RECENT_SEARCHES = 5

// The browse lists, in the order of the rows before a query.
val browseLists: List<BrowseList> = listOf(BrowseList.ThisSeason, BrowseList.TopAiring, BrowseList.TopRated, BrowseList.MostPopular)

// A list that arrives page by page.
sealed interface Paged<out T> {
    data object Loading : Paged<Nothing>

    data class Failed(
        val error: DataError,
    ) : Paged<Nothing>

    // Empty when nothing matched.
    data class Shown<out T>(
        val items: List<T>,
        val more: More = More.None,
    ) : Paged<T>
}

// What follows the last page shown.
sealed interface More {
    data object None : More

    // Another page exists; it is asked for when the end of the list comes into view.
    data class Ready(
        val offset: Int,
    ) : More

    data object Loading : More

    data class Failed(
        val error: DataError,
    ) : More
}

sealed interface SearchEvent {
    // The keyboard's search key.
    data object Submit : SearchEvent

    data object Clear : SearchEvent

    data object Back : SearchEvent

    data object Manga : SearchEvent

    data object BrowseManga : SearchEvent

    data class OpenManga(
        val id: MangaId,
    ) : SearchEvent

    data class SearchAgain(
        val query: String,
    ) : SearchEvent

    data class OpenRecent(
        val key: RecentTitleId,
    ) : SearchEvent

    data class RemoveRecent(
        val query: String,
    ) : SearchEvent

    data class Browse(
        val list: BrowseList,
    ) : SearchEvent

    data class Open(
        val id: AnimeId,
    ) : SearchEvent

    data class Edit(
        val id: AnimeId,
    ) : SearchEvent

    data object LoadMore : SearchEvent

    data object Retry : SearchEvent

    data object SignIn : SearchEvent
}

// The MAL id a query names when it is digits alone.
fun queriedId(query: String): AnimeId? =
    query
        .trim()
        .takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.let(::AnimeId)

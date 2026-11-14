package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.text.input.TextFieldState
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.BrowseList

data class SearchState(
    // The search field's text, owned by the caller and read by the field itself, so no keystroke waits (ARCHITECTURE 6.2).
    val query: TextFieldState,
    // Whether the full-screen search view is open when the screen is first composed; it then follows the user.
    val expanded: Boolean,
    val content: SearchContent,
    val sessionExpired: Boolean = false,
)

sealed interface SearchContent {
    // Before a query: the recent searches, newest first, then the browse lists.
    data class Start(
        val recent: List<String>,
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
}

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
    data object Ready : More

    data object Loading : More

    data class Failed(
        val error: DataError,
    ) : More
}

sealed interface SearchEvent {
    // The keyboard's search key.
    data object Submit : SearchEvent

    data object Clear : SearchEvent

    data class SearchAgain(
        val query: String,
    ) : SearchEvent

    data class Browse(
        val list: BrowseList,
    ) : SearchEvent

    data class Open(
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

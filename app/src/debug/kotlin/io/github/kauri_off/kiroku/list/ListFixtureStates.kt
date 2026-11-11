package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.defaultSort
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.ListWrite

// The List's states over the fixture set, at the fixtures' moment, for previews and screenshots.
object ListFixtureStates {
    private val set = Fixtures.reference
    private val today = set.now.atZone(FixtureTime.zone).toLocalDate()

    // Rows in the order computed for [ordered]; [shown] holds the entries as they are now, so an edit after the
    // order was computed changes a row in place, and a title that left the tab leaves its row.
    fun rows(
        status: ListStatus,
        sort: ListSort = defaultSort(status, withAiring = true),
        ordered: List<ListEntry> = set.entries,
        shown: List<ListEntry> = ordered,
        readOnly: Boolean = false,
    ): List<ListRow> {
        val now = shown.associateBy { it.anime.id }
        val airing = if (readOnly) emptyMap() else set.airing
        return order(ordered.filter { it.status == status }, sort, airing::get) { it.anime.titles.romaji }
            .mapNotNull { now[it.anime.id]?.takeIf { entry -> entry.status == status } }
            .map { listRow(it, airing[it.anime.id], today, TitleLanguage.Romaji, readOnly) }
    }

    private fun state(
        status: ListStatus = ListStatus.Watching,
        content: ListContent = ListContent.Rows(rows(status)),
    ) = ListState(
        owner = ListOwner.Me,
        content = content,
        tab = status,
        sort = defaultSort(status, withAiring = true),
        sorts = sortsFor(status, withAiring = true),
        now = set.now,
    )

    private fun after(
        anime: Anime,
        write: (Anime) -> ListWrite,
    ): List<ListEntry> =
        set.entries.map { entry ->
            if (entry.anime.id != anime.id) {
                entry
            } else {
                val values = write(anime).applyTo(entry, today) ?: entry.values
                entry.copy(values = values, updatedAt = set.now)
            }
        }

    val watching = state()

    val planned = state(ListStatus.Planned)

    val completed = state(ListStatus.Completed)

    val sortSheet = watching.copy(sortSheetOpen = true)

    // "Kaze", in two Watching titles and in no other.
    private val query =
        FixtureAnime.estimatedCaughtUp.titles.romaji
            .substringBefore(' ')

    val filtering =
        watching.copy(
            filter = query,
            content = ListContent.Rows(rows(ListStatus.Watching).filter { it.title.contains(query, ignoreCase = true) }),
        )

    val refreshing = watching.copy(refreshing = true)

    val undoSnackbar =
        watching.copy(
            content =
                ListContent.Rows(
                    rows(ListStatus.Watching, shown = after(FixtureAnime.exactToWatch) { ListWrite.MarkNextWatched(it) }),
                ),
            message = ListMessage(ListMessage.Kind.EpisodeWatched(13), serial = 1),
        )

    val completedSnackbar =
        watching.copy(
            content =
                ListContent.Rows(
                    rows(ListStatus.Watching, shown = after(FixtureAnime.oneShortOfTotal) { ListWrite.MarkNextWatched(it) }),
                ),
            message = ListMessage(ListMessage.Kind.MovedToCompleted, serial = 1),
        )

    val offlineCached = watching.copy(message = ListMessage(ListMessage.Kind.CouldNotRefresh, serial = 1))

    val loading = state(content = ListContent.Loading)

    val empty = state(ListStatus.OnHold, ListContent.Rows(emptyList()))

    val error = state(content = ListContent.Failed(ListFailure.NoConnection))

    val signedOut = state(content = ListContent.SignedOut)

    val sessionExpired = watching.copy(sessionExpired = true)

    val readOnly =
        ListState(
            owner = ListOwner.Other(set.otherUserName),
            content =
                ListContent.Rows(
                    rows(ListStatus.Watching, defaultSort(ListStatus.Watching, withAiring = false), set.otherUserEntries, readOnly = true),
                ),
            sort = defaultSort(ListStatus.Watching, withAiring = false),
            sorts = sortsFor(ListStatus.Watching, withAiring = false),
            now = set.now,
        )
}

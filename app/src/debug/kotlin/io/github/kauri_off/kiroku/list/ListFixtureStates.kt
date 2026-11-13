package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.list.defaultSort
import io.github.kauri_off.kiroku.core.list.order
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.shell.ShellMessage

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
        direction: SortDirection? = sort.defaultDirection,
    ): List<ListRow> {
        val now = shown.associateBy { it.anime.id }
        val airing = if (readOnly) emptyMap() else set.airing
        return order(ordered.filter { it.status == status }, sort, airing::get, today, direction) { it.anime.titles.romaji }
            .mapNotNull { now[it.anime.id]?.takeIf { entry -> entry.status == status } }
            .map { listRow(it, airing[it.anime.id], today, TitleLanguage.Romaji, readOnly, sort) }
    }

    // Every tab's page by its default sort; [override] replaces some of them.
    fun tabs(
        entries: List<ListEntry> = set.entries,
        readOnly: Boolean = false,
        filter: String? = null,
        override: Map<ListStatus, ListPage> = emptyMap(),
    ): ListContent.Tabs =
        ListContent.Tabs(
            ListStatus.entries.associateWith { status ->
                val sort = defaultSort(status, withAiring = !readOnly)
                val page =
                    override[status]
                        ?: ListPage(sort, sortsFor(status, withAiring = !readOnly), rows(status, sort, entries, readOnly = readOnly))
                page.copy(rows = page.rows.filter { filter == null || it.title.contains(filter, ignoreCase = true) })
            },
        )

    fun watchingPage(
        rows: List<ListRow>,
        sort: ListSort = defaultSort(ListStatus.Watching, withAiring = true),
    ) = mapOf(ListStatus.Watching to ListPage(sort, sortsFor(ListStatus.Watching, withAiring = true), rows))

    private fun state(
        status: ListStatus = ListStatus.Watching,
        content: ListContent = tabs(),
    ) = ListState(owner = ListOwner.Me, content = content, tab = status, now = set.now)

    private fun after(
        anime: Anime,
        write: (Anime) -> ListWrite,
    ): List<ListEntry> =
        edited(anime) { entry ->
            val values = write(anime).applyTo(entry, today) ?: entry.values
            entry.copy(values = values, updatedAt = set.now)
        }

    private fun undo(anime: Anime): ShellMessage.Action.Undo {
        val entry = set.entries.single { it.anime.id == anime.id }
        return ShellMessage.Action.Undo(ListWrite.Save(entry.anime, entry.values))
    }

    private fun edited(
        anime: Anime,
        edit: (ListEntry) -> ListEntry,
    ): List<ListEntry> = set.entries.map { if (it.anime.id == anime.id) edit(it) else it }

    // Watching in the order computed for [sort] from the fixtures, showing the entries as they are in [shown].
    private fun watchingShown(
        sort: ListSort = defaultSort(ListStatus.Watching, withAiring = true),
        shown: List<ListEntry> = set.entries,
    ): ListContent.Tabs = tabs(override = watchingPage(rows(ListStatus.Watching, sort, shown = shown), sort))

    val watching = state()

    val planned = state(ListStatus.Planned)

    val completed = state(ListStatus.Completed)

    val onHold = state(ListStatus.OnHold)

    val dropped = state(ListStatus.Dropped)

    val sortSheet = watching.copy(sortSheetOpen = true)

    // [status] ordered by [sort] in [direction], each row showing the value it is sorted by where it has one.
    fun sorted(
        status: ListStatus,
        sort: ListSort,
        direction: SortDirection? = sort.defaultDirection,
        readOnly: Boolean = false,
    ): ListState {
        val entries = if (readOnly) set.otherUserEntries else set.entries
        val page =
            ListPage(
                sort,
                sortsFor(status, withAiring = !readOnly),
                rows(status, sort, entries, readOnly = readOnly, direction = direction),
                direction = direction,
            )
        val tabs = tabs(entries, readOnly, override = mapOf(status to page))
        val owner = if (readOnly) ListOwner.Other(set.otherUserName) else ListOwner.Me
        return ListState(owner = owner, content = tabs, tab = status, now = set.now)
    }

    val sortSheetTitle = sorted(ListStatus.Watching, ListSort.Title).copy(sortSheetOpen = true)

    val sortSheetLastUpdated = sorted(ListStatus.Watching, ListSort.LastUpdated).copy(sortSheetOpen = true)

    // The direction other than the sort's own, chosen.
    val sortSheetScoreLowest = sorted(ListStatus.Watching, ListSort.Score, SortDirection.Ascending).copy(sortSheetOpen = true)

    val sortSheetStartDate = sorted(ListStatus.Watching, ListSort.StartDate).copy(sortSheetOpen = true)

    val watchingByScore = sorted(ListStatus.Watching, ListSort.Score)

    val watchingByStartDate = sorted(ListStatus.Watching, ListSort.StartDate, SortDirection.Descending)

    val plannedByStartDate = sorted(ListStatus.Planned, ListSort.StartDate)

    val completedByScore = sorted(ListStatus.Completed, ListSort.Score)

    val completedByStartDate = sorted(ListStatus.Completed, ListSort.StartDate)

    // "Kaze", in two Watching titles and in no other.
    private val query =
        FixtureAnime.estimatedCaughtUp.titles.romaji
            .substringBefore(' ')

    val filtering = watching.copy(filter = query, content = tabs(filter = query))

    val refreshing = watching.copy(refreshing = true)

    // After "+1" on the top title; the shell shows [undoMessage] over it.
    val undoSnackbar = watching.copy(content = watchingShown(shown = after(FixtureAnime.exactToWatch) { ListWrite.MarkNextWatched(it) }))

    val undoMessage = ShellMessage(ShellMessage.Text.EpisodeWatched(13), undo(FixtureAnime.exactToWatch))

    // After "+1" completed a title; the shell shows [completedMessage] over it.
    val completedSnackbar =
        watching.copy(content = watchingShown(shown = after(FixtureAnime.oneShortOfTotal) { ListWrite.MarkNextWatched(it) }))

    val completedMessage = ShellMessage(ShellMessage.Text.MovedToCompleted, undo(FixtureAnime.oneShortOfTotal))

    // Watching by "Last updated", with the title "+1" is about to complete on top.
    val lastUpdated = watching.copy(content = watchingShown(ListSort.LastUpdated))

    // The same after "+1" completed the top title, before any message: the row is gone and the rest closed up.
    val completedRow =
        lastUpdated.copy(
            content = watchingShown(ListSort.LastUpdated, after(FixtureAnime.oneShortOfTotal) { ListWrite.MarkNextWatched(it) }),
        )

    // A count above the total is recorded and shown as it is (W9): "Watched 25 of 24".
    val aboveTotal =
        watching.copy(
            content =
                watchingShown(
                    shown = edited(FixtureAnime.finishedPartWay) { it.copy(values = it.values.copy(watchedEpisodes = 25)) },
                ),
        )

    // A refresh failed with the list cached; the shell shows [offlineMessage] over it.
    val offlineCached = watching

    val offlineMessage = ShellMessage(ShellMessage.Text.RefreshFailed(DataError.NoConnection))

    val loading = state(content = ListContent.Loading)

    val empty =
        state(
            ListStatus.OnHold,
            tabs(
                override =
                    mapOf(
                        ListStatus.OnHold to
                            ListPage(
                                defaultSort(ListStatus.OnHold, withAiring = true),
                                sortsFor(ListStatus.OnHold, withAiring = true),
                                emptyList(),
                            ),
                    ),
            ),
        )

    val error = state(content = ListContent.Failed(ListFailure.NoConnection))

    val signedOut = state(content = ListContent.SignedOut)

    val sessionExpired = watching.copy(sessionExpired = true)

    val readOnly =
        ListState(
            owner = ListOwner.Other(set.otherUserName),
            content = tabs(set.otherUserEntries, readOnly = true),
            now = set.now,
        )

    val readOnlyByScore = sorted(ListStatus.Watching, ListSort.Score, readOnly = true)
}

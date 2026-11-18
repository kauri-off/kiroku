package io.github.kauri_off.kiroku.list

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.notShown

// Another user's list over the fixture set, read-only, for previews and screenshots.
object UserListFixtureStates {
    private val set = Fixtures.reference
    val name = set.otherUserName
    private val UNKNOWN_NAME = notShown("no_such_user")

    // The list of several thousand titles, of which a screenshot shows the first that were read.
    private val large = set.otherUserList

    private fun state(
        entries: List<ListEntry>?,
        tab: EntryStatus = ListStatus.Watching,
        reading: UserListProgress? = null,
        owner: String = name,
    ) = ListState(
        owner = ListOwner.Other(owner),
        content = entries?.let { ListContent.Tabs(ListFixtureStates.tabs(it, readOnly = true).pages) } ?: ListContent.Loading,
        tab = tab,
        now = set.now,
        reading = reading,
        listSize = entries?.size?.takeIf { reading == null },
    )

    private fun failed(
        failure: ListFailure,
        owner: String = name,
    ) = ListState(ListOwner.Other(owner), ListContent.Failed(failure), now = set.now)

    val watching = state(set.otherUserEntries)

    val completed = state(set.otherUserEntries, ListStatus.Completed)

    // A tab with no title on this list.
    val empty = state(set.otherUserEntries, ListStatus.OnHold)

    val loading = state(null)

    // A tab whose name is longer than the tab row is wide: a status the app does not know, by MAL's code.
    val longestTab =
        state(
            set.otherUserEntries.map {
                if (it.anime.id == FixtureAnime.film.id) it.copy(values = it.values.copy(status = Fixtures.longUnknownListStatus)) else it
            },
            Fixtures.longUnknownListStatus,
        )

    val byScore = ListFixtureStates.sorted(ListStatus.Watching, ListSort.Score, readOnly = true).copy(listSize = set.otherUserEntries.size)

    // The first titles of the list, as MAL answers: no total.
    private val readSoFar = large.take(READ_SO_FAR)

    val reading = state(readSoFar, reading = UserListProgress(readSoFar.size, null))

    // The same where the source names the list's size.
    val readingWithTotal = state(readSoFar, reading = UserListProgress(readSoFar.size, NAMED_TOTAL))

    // A pull reads the list again under the one shown: at its start, and some titles in.
    val readAgain = watching.copy(reading = UserListProgress(0, null), readingAgain = true)

    val readAgainPart = watching.copy(reading = UserListProgress(READ_SO_FAR, null), readingAgain = true)

    // Its own last tab, named by MAL's code.
    val unknownStatus =
        state(
            set.otherUserEntries.map {
                if (it.anime.id == FixtureAnime.film.id) it.copy(values = it.values.copy(status = Fixtures.unknownListStatus)) else it
            },
            Fixtures.unknownListStatus,
        )

    val filtering =
        watching.copy(
            filter =
                FixtureAnime.longTitle.titles.romaji
                    .substringBefore(' '),
            content =
                ListFixtureStates.tabs(
                    set.otherUserEntries,
                    readOnly = true,
                    filter =
                        FixtureAnime.longTitle.titles.romaji
                            .substringBefore(' '),
                ),
        )

    val sessionExpired = watching.copy(sessionExpired = true)

    val sortSheet = byScore.copy(sortSheetOpen = true)

    val privateList = failed(ListFailure.PrivateList, set.privateUserName)

    val unknownUser = failed(ListFailure.UnknownUser, UNKNOWN_NAME)

    val noConnection = failed(ListFailure.NoConnection)

    val refused = failed(ListFailure.Refused)

    val other = failed(ListFailure.Other)

    private const val READ_SO_FAR = 40
    private const val NAMED_TOTAL = 100
}

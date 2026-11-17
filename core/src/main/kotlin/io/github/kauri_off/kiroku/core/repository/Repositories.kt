package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.model.over
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

interface SessionRepository {
    // Emits nothing until the token store was read.
    fun observeSession(): Flow<Session>

    suspend fun signOut()
}

sealed interface ListWrite {
    val anime: Anime

    // Sets [only] these values over the entry as it is when sent, or all of them.
    data class Save(
        override val anime: Anime,
        val values: ListValues,
        val only: Set<ListField>? = null,
    ) : ListWrite {
        init {
            require(only == null || only.isNotEmpty())
        }

        fun over(current: ListValues?): ListValues = if (only == null || current == null) values else values.over(current, only)
    }

    data class Remove(
        override val anime: Anime,
    ) : ListWrite

    data class MarkWatched(
        override val anime: Anime,
        val episode: Int,
    ) : ListWrite {
        init {
            require(episode > 0)
        }
    }

    data class MarkNextWatched(
        override val anime: Anime,
    ) : ListWrite

    // A count the user typed, applied to the entry's other values as they are when sent (W7, W9).
    data class SetWatched(
        override val anime: Anime,
        val watched: Int,
    ) : ListWrite {
        init {
            require(watched >= 0)
        }
    }

    // A stepper's plus or minus, applied to the count as it is when sent; never below zero.
    data class StepWatched(
        override val anime: Anime,
        val by: Int,
    ) : ListWrite

    // W3: takes back [change] alone, from the entry's values as they are when sent.
    data class Undo(
        val change: ListChange,
    ) : ListWrite {
        override val anime: Anime get() = change.anime
    }

    // Applies again, as a new edit, what the failed write set that nothing has changed since (W2).
    data class Retry(
        val failure: WriteFailure,
    ) : ListWrite {
        override val anime: Anime get() = failure.write.anime
    }

    // "+1", the stepper and "Mark watched" move the count by a step rather than set it (section 7.3).
    val steps: Boolean
        get() = this is MarkWatched || this is MarkNextWatched || this is StepWatched || (this is Retry && failure.write.steps)

    // An Undo and a Retry belong to the list they were made in, and no other list takes them.
    val bound: Boolean get() = this is Undo || this is Retry

    val list: SignInId?
        get() =
            when (this) {
                is Undo -> change.list
                is Retry -> failure.list
                else -> null
            }

    fun applyTo(
        current: ListEntry?,
        today: LocalDate,
    ): ListValues? =
        when (this) {
            is Save -> over(current?.values)
            is Remove -> null
            is MarkWatched ->
                current?.let {
                    if (it.values.watchedEpisodes == episode - 1) {
                        it.values.markWatched(episode, it.anime.malEpisodes, today)
                    } else {
                        it.values
                    }
                }
            is MarkNextWatched ->
                current?.let { it.values.markWatched(it.values.watchedEpisodes + 1, it.anime.malEpisodes, today) }
            is SetWatched -> current?.let { it.values.withWatched(watched, it.anime.malEpisodes, today) }
            is StepWatched ->
                current?.let {
                    it.values.withWatched((it.values.watchedEpisodes + by).coerceAtLeast(0), it.anime.malEpisodes, today)
                }
            is Undo -> change.revert(current)
            is Retry -> failure.left(current?.values)?.applyTo(current, today) ?: current?.values
        }
}

// What one write did to its entry in the list of one sign-in, read and applied as one step (W3).
data class ListChange(
    val before: ListEntry?,
    val after: ListEntry?,
    val stepped: Boolean,
    val list: SignInId,
) {
    init {
        require(before != null || after != null)
    }

    val anime: Anime get() = (after ?: before)!!.anime

    // None for a change off a status the app does not know: that status is never sent, so it cannot be put back.
    val undo: ListWrite?
        get() = if (before?.status is EntryStatus.Unrecognized && after?.status != before.status) null else ListWrite.Undo(this)

    // W7 moved the title to Completed.
    val completed: Boolean get() = after?.status == ListStatus.Completed && before?.status != ListStatus.Completed

    // A stepped count steps back; any other value goes back only while it still holds what this change put there.
    internal fun revert(current: ListEntry?): ListValues? {
        val before = before?.values ?: return null
        val after = after?.values ?: return current?.values ?: before
        val now = current?.values ?: return null

        fun <T> back(field: (ListValues) -> T): T = if (field(now) == field(after)) field(before) else field(now)
        val watched =
            if (stepped) {
                (now.watchedEpisodes - (after.watchedEpisodes - before.watchedEpisodes)).coerceAtLeast(0)
            } else {
                back { it.watchedEpisodes }
            }
        return ListValues(back { it.status }, watched, back { it.score }, back { it.startDate }, back { it.finishDate })
    }
}

// A write that failed in the list of one sign-in; no sign-in when it was refused signed out.
data class WriteFailure(
    val error: DataError,
    val write: ListWrite,
    val list: SignInId?,
    // Null for a write that was never applied; its Retry then sets all it carries.
    val untouched: Untouched? = null,
) {
    val retry: ListWrite.Retry get() = ListWrite.Retry(this)

    // W2: what a Retry over [current] still sets, each unit changed since the failure left out; null when none is left.
    fun left(current: ListValues?): ListWrite? {
        val untouched = untouched ?: return write
        return when (write) {
            is ListWrite.Save -> {
                val named = write.only ?: ListField.entries.toSet()
                val held = untouched.values + write.values
                val kept =
                    ListField.units.flatMapTo(mutableSetOf()) { unit ->
                        val set = unit.filterTo(mutableSetOf()) { it in named }
                        // The fields it sets and the status, so a date outlives a newer step that leaves the status alone.
                        val judged = if (ListField.Status in unit) set + ListField.Status else set
                        if (set.isNotEmpty() && held.any { it.agrees(current, judged) }) set else emptySet()
                    }
                when {
                    kept.isEmpty() -> null
                    kept == named -> write
                    else -> write.copy(only = kept)
                }
            }
            is ListWrite.Remove -> write.takeIf { current == null || current in untouched.values }
            else -> write
        }
    }

    // A Retry over [current] would change nothing: what it sets is there, or what was there changed since.
    fun settledBy(current: ListValues?): Boolean =
        when (val left = left(current)) {
            null -> true
            is ListWrite.Save -> current != null && left.over(current) == current
            is ListWrite.Remove -> current == null
            else -> false
        }
}

// Both are no entry, or both hold the same value in each of [fields].
private fun ListValues?.agrees(
    other: ListValues?,
    fields: Set<ListField>,
): Boolean = if (this == null || other == null) this == other else fields.all { it.of(this) == it.of(other) }

// What a title may hold that nobody changed since a write failed: MAL's confirmed values, and the request's if made.
data class Untouched(
    // Null is no entry.
    val values: Set<ListValues?>,
)

// The list as a screen sees it: read and synced, never written, so a write can only go through the shell.
interface ListSource {
    // Ends with a DataException when the list cannot be read; a new collection tries again.
    fun observeEntries(): Flow<List<ListEntry>>

    fun observeEntry(id: AnimeId): Flow<ListEntry?>

    // The entries of [ids] that exist, in one read of the store however many there are.
    fun observeEntriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>>

    // When the whole list was last downloaded; null when it never was, or when a sync is owed (W5).
    fun observeSyncedAt(): Flow<Instant?>

    suspend fun sync(): Outcome<Unit>
}

interface ListRepository : ListSource {
    // One collector hears each failure; one made with none waits for the next, until its list ends or MAL holds its values.
    fun observeWriteFailures(): Flow<WriteFailure>

    // Applies the write locally, whole even when its caller is cancelled; null when it changed nothing or was refused.
    // A refusal is a write failure: `SessionExpired`, or `ListEnded` for an Undo or a Retry of another list.
    suspend fun write(write: ListWrite): ListChange?

    suspend fun send(write: ListWrite): Outcome<Unit>
}

interface AnimeRepository {
    fun observeAnime(id: AnimeId): Flow<Anime?>

    fun observeDetails(id: AnimeId): Flow<AnimeDetails?>

    // The stored details of those of [ids] that have them, in one read of the store however many there are.
    fun observeDetailsOf(ids: Set<AnimeId>): Flow<Map<AnimeId, AnimeDetails>>

    // When the details were last fetched; null when they never were.
    fun observeRefreshedAt(id: AnimeId): Flow<Instant?>

    // The titles whose page is stored.
    fun observePagesRead(): Flow<Set<AnimeId>>

    suspend fun refresh(id: AnimeId): Outcome<Unit>
}

// Reading asks the source of episode times nothing: only a refresh does, and a change of the list (A11, a sync's end).
interface AiringRepository {
    // The result of each title of the list that can still air (A1), given again to every observer when one changes (A2).
    fun observeAiring(): Flow<Map<AnimeId, Airing>>

    // For the page of [page] alone, never for a notification: the same with [page]'s own result, also from what the page fetched (A12).
    fun observePageAiring(page: Anime): Flow<Map<AnimeId, Airing>>

    // The stored schedules, which are all a notification may read.
    fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>>

    // Asks for the tracked titles whose data is stale, as a screen does when it is shown.
    suspend fun refreshStale(): Outcome<Unit>

    // Asks for every tracked title, as a person's pull does.
    suspend fun refresh(): Outcome<Unit>

    // A page that is shown: asks for [page]'s title when what is held of it is stale; never for a finished one.
    suspend fun refreshStale(page: Anime): Outcome<Unit>

    // A pull on a page.
    suspend fun refresh(page: Anime): Outcome<Unit>
}

enum class BrowseList { ThisSeason, TopAiring, TopRated, MostPopular }

data class Page<out T>(
    val items: List<T>,
    val nextOffset: Int?,
)

data class Ranked<out T>(
    val position: Int?,
    val item: T,
)

interface SearchRepository {
    suspend fun search(
        query: String,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Anime>>

    suspend fun browse(
        list: BrowseList,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Ranked<Anime>>>
}

sealed interface UserListLoad {
    data class Loading(
        val loaded: Int,
        val total: Int?,
    ) : UserListLoad

    data class Loaded(
        val entries: List<ListEntry>,
    ) : UserListLoad

    data class Failed(
        val error: DataError,
    ) : UserListLoad
}

interface UserRepository {
    fun observeOwnProfile(): Flow<UserProfile?>

    suspend fun refreshOwnProfile(): Outcome<Unit>

    fun loadList(userName: String): Flow<UserListLoad>
}

// The person's choices, kept across restarts. A choice that cannot be read counts as never made, and is reported.
interface PreferencesRepository {
    // Every tab of the user's own list with its sort: the one chosen, or the tab's default.
    fun observeListSorts(): Flow<Map<ListStatus, TabSort>>

    suspend fun setListSort(
        status: ListStatus,
        sort: TabSort,
    )
}

package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface SessionRepository {
    // Emits nothing until the token store was read.
    fun observeSession(): Flow<Session>

    suspend fun signOut()
}

sealed interface ListWrite {
    val anime: Anime

    data class Save(
        override val anime: Anime,
        val values: ListValues,
    ) : ListWrite

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

    // "+1", the stepper and "Mark watched" move the count by a step rather than set it (section 7.3).
    val steps: Boolean get() = this is MarkWatched || this is MarkNextWatched || this is StepWatched

    fun applyTo(
        current: ListEntry?,
        today: LocalDate,
    ): ListValues? =
        when (this) {
            is Save -> values
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
        }
}

// What one write did to its entry, read and applied as one step, so no other write comes between them (W3).
data class ListChange(
    val before: ListEntry?,
    val after: ListEntry?,
    val stepped: Boolean,
) {
    init {
        require(before != null || after != null)
    }

    val anime: Anime get() = (after ?: before)!!.anime

    val undo: ListWrite get() = ListWrite.Undo(this)

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

data class WriteFailure(
    val error: DataError,
    val retry: ListWrite,
)

// The list as a screen sees it: read and synced, never written, so a write can only go through the shell.
interface ListSource {
    // Ends with a DataException when the list cannot be read; a new collection tries again.
    fun observeEntries(): Flow<List<ListEntry>>

    fun observeEntry(id: AnimeId): Flow<ListEntry?>

    suspend fun sync(): Outcome<Unit>
}

interface ListRepository : ListSource {
    // Hot (section 7.3): dropped with no collector, buffered for a slow one.
    fun observeWriteFailures(): Flow<WriteFailure>

    // Applies the write locally, whole even when its caller is cancelled; null when it changed nothing or was refused.
    suspend fun write(write: ListWrite): ListChange?

    suspend fun send(write: ListWrite): Outcome<Unit>
}

interface AnimeRepository {
    fun observeAnime(id: AnimeId): Flow<Anime?>

    fun observeDetails(id: AnimeId): Flow<AnimeDetails?>

    suspend fun refresh(id: AnimeId): Outcome<Unit>
}

interface AiringRepository {
    fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>>

    // Every screen's airing lines; emits again when an air time passes (A2) or the data changes. Provisional until M3.
    fun observeAiring(): Flow<Map<AnimeId, Airing>>

    suspend fun refresh(ids: Set<AnimeId>): Outcome<Unit>

    suspend fun fetch(id: AnimeId): Outcome<AiringSchedule?>
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

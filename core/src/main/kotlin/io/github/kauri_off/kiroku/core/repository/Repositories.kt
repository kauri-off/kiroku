package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface SessionRepository {
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

    fun applyTo(
        current: ListEntry?,
        today: LocalDate,
    ): ListValues? =
        when (this) {
            is Save -> values
            is Remove -> null
            is MarkWatched -> current?.let { it.values.markWatched(episode, it.anime.malEpisodes, today) }
            is MarkNextWatched ->
                current?.let { it.values.markWatched(it.values.watchedEpisodes + 1, it.anime.malEpisodes, today) }
        }
}

data class WriteFailure(
    val error: DataError,
    val retry: ListWrite,
)

interface ListRepository {
    fun observeEntries(): Flow<List<ListEntry>>

    fun observeEntry(id: AnimeId): Flow<ListEntry?>

    fun observeWriteFailures(): Flow<WriteFailure>

    suspend fun sync(): Outcome<Unit>

    suspend fun write(write: ListWrite)

    suspend fun send(write: ListWrite): Outcome<Unit>
}

interface AnimeRepository {
    fun observeAnime(id: AnimeId): Flow<Anime?>

    fun observeDetails(id: AnimeId): Flow<AnimeDetails?>

    suspend fun refresh(id: AnimeId): Outcome<Unit>
}

interface AiringRepository {
    fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>>

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

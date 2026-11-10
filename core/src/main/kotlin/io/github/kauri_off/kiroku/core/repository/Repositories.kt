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

interface SessionRepository {
    val session: Flow<Session>

    suspend fun signOut()
}

interface ListRepository {
    fun observeEntries(): Flow<List<ListEntry>>

    suspend fun sync(): Outcome<Unit>

    suspend fun save(
        anime: Anime,
        values: ListValues,
    ): Outcome<Unit>

    suspend fun remove(id: AnimeId): Outcome<Unit>
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
    ): Outcome<Page<Anime>>

    suspend fun browse(
        list: BrowseList,
        offset: Int,
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

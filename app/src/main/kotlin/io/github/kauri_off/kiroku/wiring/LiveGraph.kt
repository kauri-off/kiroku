package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserListLoad
import io.github.kauri_off.kiroku.core.repository.UserRepository
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.time.Clock

// Signed out and without a remote source.
internal class LiveGraph(
    override val clock: Clock,
) : AppGraph {
    private val unavailable = Outcome.Failure(DataError.ServerError)
    private val signedOut = Outcome.Failure(DataError.SessionExpired)

    override val session =
        object : SessionRepository {
            override fun observeSession(): Flow<Session> = flowOf(Session.SignedOut)

            override suspend fun signOut() = Unit
        }

    override val list =
        object : ListRepository {
            override fun observeEntries(): Flow<List<ListEntry>> = flowOf(emptyList())

            override fun observeEntry(id: AnimeId): Flow<ListEntry?> = flowOf(null)

            override fun observeWriteFailures(): Flow<WriteFailure> = emptyFlow()

            override suspend fun sync(): Outcome<Unit> = signedOut

            override suspend fun write(write: ListWrite): ListChange? = null

            override suspend fun send(write: ListWrite): Outcome<Unit> = signedOut
        }

    override val anime =
        object : AnimeRepository {
            override fun observeAnime(id: AnimeId): Flow<Anime?> = flowOf(null)

            override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = flowOf(null)

            override suspend fun refresh(id: AnimeId): Outcome<Unit> = unavailable
        }

    override val airing =
        object : AiringRepository {
            override fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> = flowOf(emptyMap())

            override fun observeAiring(): Flow<Map<AnimeId, Airing>> = flowOf(emptyMap())

            override suspend fun refresh(ids: Set<AnimeId>): Outcome<Unit> = unavailable

            override suspend fun fetch(id: AnimeId): Outcome<AiringSchedule?> = unavailable
        }

    override val search =
        object : SearchRepository {
            override suspend fun search(
                query: String,
                offset: Int,
                includeAdult: Boolean,
            ): Outcome<Page<Anime>> = unavailable

            override suspend fun browse(
                list: BrowseList,
                offset: Int,
                includeAdult: Boolean,
            ): Outcome<Page<Ranked<Anime>>> = unavailable
        }

    override val user =
        object : UserRepository {
            override fun observeOwnProfile(): Flow<UserProfile?> = flowOf(null)

            override suspend fun refreshOwnProfile(): Outcome<Unit> = signedOut

            override fun loadList(userName: String): Flow<UserListLoad> = flowOf(UserListLoad.Failed(DataError.ServerError))
        }
}

package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureSet
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserListLoad
import io.github.kauri_off.kiroku.core.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class DemoSessionRepository(
    private val signedIn: MutableStateFlow<Boolean>,
    private val server: DemoServer,
    private val userName: String,
) : SessionRepository {
    override fun observeSession(): Flow<Session> =
        combine(signedIn, server.sessionExpired) { signedIn, expired ->
            when {
                !signedIn -> Session.SignedOut
                expired -> Session.Expired
                else -> Session.SignedIn(userName)
            }
        }

    override suspend fun signOut() {
        signedIn.value = false
    }
}

internal class DemoAnimeRepository(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
) : AnimeRepository {
    private val anime = fixtures.anime.associateBy { it.id }
    private val cached = MutableStateFlow(emptyMap<AnimeId, AnimeDetails>())

    override fun observeAnime(id: AnimeId): Flow<Anime?> = flowOf(anime[id])

    override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = cached.map { it[id] }

    override suspend fun refresh(id: AnimeId): Outcome<Unit> {
        server.load(needsSignIn = false)?.let { return Outcome.Failure(it) }
        val details = fixtures.details[id] ?: return Outcome.Failure(DataError.NotFound)
        cached.value += id to details
        return Outcome.Success(Unit)
    }
}

internal class DemoAiringRepository(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
) : AiringRepository {
    override fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> = flowOf(fixtures.schedules)

    override suspend fun refresh(ids: Set<AnimeId>): Outcome<Unit> = server.load(needsSignIn = false).toOutcome()

    override suspend fun fetch(id: AnimeId): Outcome<AiringSchedule?> =
        server.load(needsSignIn = false)?.let { Outcome.Failure(it) } ?: Outcome.Success(fixtures.schedules[id])
}

internal class DemoSearchRepository(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
) : SearchRepository {
    override suspend fun search(
        query: String,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Anime>> {
        server.load(needsSignIn = false)?.let { return Outcome.Failure(it) }
        val text = query.trim()
        val found =
            fixtures.anime.filter { anime ->
                with(anime.titles) { (listOfNotNull(romaji, english, japanese) + synonyms).any { it.contains(text, ignoreCase = true) } }
            }
        return Outcome.Success(found.page(offset))
    }

    override suspend fun browse(
        list: BrowseList,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Ranked<Anime>>> {
        server.load(needsSignIn = false)?.let { return Outcome.Failure(it) }
        val ranked =
            when (list) {
                BrowseList.ThisSeason ->
                    fixtures.anime.filter { it.airingStatus != AiringStatus.Finished }.map { Ranked(null, it) }
                BrowseList.TopAiring -> byRank(fixtures.anime.filter { it.airingStatus == AiringStatus.Airing })
                BrowseList.TopRated -> byRank(fixtures.anime)
                BrowseList.MostPopular ->
                    fixtures.anime
                        .sortedByDescending { fixtures.details[it.id]?.statistics?.members ?: 0 }
                        .mapIndexed { index, anime -> Ranked(index + 1, anime) }
            }
        return Outcome.Success(ranked.page(offset))
    }

    private fun byRank(anime: List<Anime>): List<Ranked<Anime>> =
        anime
            .filter { it.rank != null }
            .sortedBy { it.rank }
            .mapIndexed { index, it -> Ranked(index + 1, it) }

    private fun <T> List<T>.page(offset: Int): Page<T> {
        val items = drop(offset).take(PAGE_SIZE)
        val next = offset + items.size
        return Page(items, next.takeIf { it < size })
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

internal class DemoUserRepository(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
    private val signedIn: MutableStateFlow<Boolean>,
) : UserRepository {
    override fun observeOwnProfile(): Flow<UserProfile?> = signedIn.map { if (it) fixtures.user else null }

    override suspend fun refreshOwnProfile(): Outcome<Unit> = server.load(needsSignIn = true).toOutcome()

    override fun loadList(userName: String): Flow<UserListLoad> =
        flow {
            val entries =
                when {
                    userName.equals(fixtures.otherUserName, ignoreCase = true) -> fixtures.otherUserEntries
                    userName.equals(fixtures.user.name, ignoreCase = true) -> server.entries()
                    else -> null
                }
            emit(UserListLoad.Loading(0, entries?.size))
            val error = server.load(needsSignIn = false) ?: DataError.UnknownUser.takeIf { entries == null }
            emit(if (error != null) UserListLoad.Failed(error) else UserListLoad.Loaded(checkNotNull(entries)))
        }
}

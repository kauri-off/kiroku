package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.AiringSource
import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.airing.effective
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureSet
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.BrowseList
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserListLoad
import io.github.kauri_off.kiroku.core.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

internal class DemoSessionRepository(
    private val signIn: MutableStateFlow<SignInId?>,
    private val server: DemoServer,
) : SessionRepository {
    override fun observeSession(): Flow<Session> =
        combine(signIn, server.sessionExpired) { signIn, expired ->
            when {
                signIn == null -> Session.SignedOut
                expired -> Session.Expired(signIn)
                else -> Session.SignedIn(signIn)
            }
        }

    override suspend fun signOut() {
        signIn.value = null
    }
}

internal class DemoAnimeRepository(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
    private val clock: Clock,
) : AnimeRepository {
    private class Fetched(
        val details: AnimeDetails,
        val at: Instant,
    )

    private val anime = fixtures.anime.associateBy { it.id }
    private val cached = MutableStateFlow(emptyMap<AnimeId, Fetched>())

    override fun observeAnime(id: AnimeId): Flow<Anime?> = flowOf(anime[id])

    override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = cached.map { it[id]?.details }.distinctUntilChanged()

    override fun observeDetailsOf(ids: Set<AnimeId>): Flow<Map<AnimeId, AnimeDetails>> =
        cached.map { cached -> cached.filterKeys(ids::contains).mapValues { it.value.details } }.distinctUntilChanged()

    override fun observeRefreshedAt(id: AnimeId): Flow<Instant?> = cached.map { it[id]?.at }.distinctUntilChanged()

    override fun observePagesRead(): Flow<Set<AnimeId>> = cached.map { it.keys }.distinctUntilChanged()

    override suspend fun refresh(id: AnimeId): Outcome<Unit> {
        server.load(needsSignIn = false)?.let { return Outcome.Failure(it) }
        val details = fixtures.details[id] ?: return Outcome.Failure(DataError.NotFound)
        cached.value += id to Fetched(details, clock.instant())
        return Outcome.Success(Unit)
    }

    fun forget() {
        cached.value = emptyMap()
    }
}

// The scenario panel's "offline" is the demo's connection.
internal class DemoConnection(
    private val server: DemoServer,
) : Connection {
    override fun observeOnline(): Flow<Boolean> = server.offline.map { !it }
}

// The demo's source of episode times: the fixtures' schedules, whole, behind the demo server's failures.
class DemoAiringSource internal constructor(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
) : AiringSource {
    private val received = CopyOnWriteArrayList<Set<AnimeId>>()

    // The titles of each request received, answered or not.
    val requests: List<Set<AnimeId>> get() = received.toList()

    // What the source has, as AniList's schedules change.
    @Volatile
    var schedules: Map<AnimeId, AiringSchedule> = fixtures.schedules

    // Every request fails so, as AniList alone failing.
    @Volatile
    var failure: DataError? = null

    override suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>> {
        received += titles.mapTo(mutableSetOf()) { it.id }
        val error = server.load(needsSignIn = false) ?: failure
        return titles.associate { anime ->
            anime.id to (error?.let { Outcome.Failure(it) } ?: Outcome.Success(schedules[anime.id]?.let { FetchedAiring(it) }))
        }
    }
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
        if (text.length < MIN_QUERY || offset < 0) return Outcome.Failure(DataError.InvalidRequest)
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
        if (offset < 0) return Outcome.Failure(DataError.InvalidRequest)
        val ranked =
            when (list) {
                BrowseList.ThisSeason ->
                    fixtures.anime.filter { it.airingStatus != AiringStatus.Finished }.map { Ranked(null, it) }
                BrowseList.TopAiring ->
                    byRank(fixtures.anime.filter { it.airingStatus.effective == AiringStatus.Airing })
                        .mapIndexed { index, it -> Ranked(index + 1, it) }
                BrowseList.TopRated -> byRank(fixtures.anime).map { Ranked(it.rank, it) }
                BrowseList.MostPopular ->
                    fixtures.anime
                        .sortedByDescending { fixtures.details[it.id]?.statistics?.members ?: 0 }
                        .mapIndexed { index, anime -> Ranked(index + 1, anime) }
            }
        return Outcome.Success(ranked.page(offset))
    }

    private fun byRank(anime: List<Anime>): List<Anime> = anime.filter { it.rank != null }.sortedBy { it.rank }

    private fun <T> List<T>.page(offset: Int): Page<T> {
        val items = drop(offset).take(PAGE_SIZE)
        val next = offset + items.size
        return Page(items, next.takeIf { it < size })
    }

    private companion object {
        const val PAGE_SIZE = 20
        const val MIN_QUERY = 3
    }
}

internal class DemoUserRepository(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
    private val signIn: MutableStateFlow<SignInId?>,
) : UserRepository {
    override fun observeOwnProfile(): Flow<UserProfile?> = signIn.map { if (it != null) fixtures.user else null }

    override suspend fun refreshOwnProfile(): Outcome<Unit> = server.load(needsSignIn = true).toOutcome()

    override fun loadList(userName: String): Flow<UserListLoad> =
        flow {
            val private = userName.equals(fixtures.privateUserName, ignoreCase = true)
            val entries =
                when {
                    userName.equals(fixtures.otherUserName, ignoreCase = true) -> fixtures.otherUserEntries
                    userName.equals(fixtures.user.name, ignoreCase = true) -> server.entries()
                    else -> null
                }
            emit(UserListLoad.Loading(0, entries?.size))
            val error =
                server.load(needsSignIn = false)
                    ?: DataError.PrivateList.takeIf { private }
                    ?: DataError.UnknownUser.takeIf { entries == null }
            if (error != null || entries == null) {
                emit(UserListLoad.Failed(error ?: DataError.UnknownUser))
                return@flow
            }
            for ((index, page) in entries.chunked(PAGE_SIZE).withIndex()) {
                if (index > 0) server.load(needsSignIn = false)?.let { return@flow emit(UserListLoad.Failed(it)) }
                emit(UserListLoad.Loading(index * PAGE_SIZE + page.size, entries.size))
            }
            emit(UserListLoad.Loaded(entries))
        }

    private companion object {
        const val PAGE_SIZE = 10
    }
}

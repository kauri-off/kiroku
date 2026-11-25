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
import io.github.kauri_off.kiroku.core.repository.BrowseSession
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.OwnProfile
import io.github.kauri_off.kiroku.core.repository.Page
import io.github.kauri_off.kiroku.core.repository.Ranked
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.score.ScoreDistribution
import io.github.kauri_off.kiroku.core.score.ScoreDistributionSource
import io.github.kauri_off.kiroku.core.user.RemoteUserLists
import io.github.kauri_off.kiroku.core.user.UserListPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

internal class DemoSessionRepository(
    private val signIn: MutableStateFlow<SignInId?>,
    private val server: DemoServer,
    // What a sign-out takes with it beyond the list (S9).
    private val forget: () -> Unit,
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
        forget()
    }
}

internal class DemoAnimeRepository(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
    private val clock: Clock,
    private val choices: DemoPreferences,
) : AnimeRepository {
    private class Fetched(
        val details: AnimeDetails,
        val at: Instant,
    )

    private val anime = fixtures.anime.associateBy { it.id }

    // The made-up titles of the public user's list: each has a page, with nothing beyond what the list gave.
    private val madeUp by lazy {
        fixtures.otherUserList
            .map { it.anime }
            .filter { it.id !in anime }
            .associateBy { it.id }
    }

    private val cached = MutableStateFlow(emptyMap<AnimeId, Fetched>())

    override fun observeAnime(id: AnimeId): Flow<Anime?> = flowOf(anime[id] ?: madeUp[id])

    override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> = cached.map { it[id]?.details }.distinctUntilChanged()

    override fun observeDetailsOf(ids: Set<AnimeId>): Flow<Map<AnimeId, AnimeDetails>> =
        cached.map { cached -> cached.filterKeys(ids::contains).mapValues { it.value.details } }.distinctUntilChanged()

    override fun observeRefreshedAt(id: AnimeId): Flow<Instant?> = cached.map { it[id]?.at }.distinctUntilChanged()

    override fun observePagesRead(): Flow<Set<AnimeId>> = cached.map { it.keys }.distinctUntilChanged()

    override suspend fun refresh(id: AnimeId): Outcome<Unit> {
        val includeAdult = choices.observeAdultInSearch().first()
        server.load(needsSignIn = false)?.let { return Outcome.Failure(it) }
        val details = fixtures.details[id] ?: madeUp[id]?.let { bare(it.id) } ?: return Outcome.Failure(DataError.NotFound)
        cached.value += id to Fetched(details.copy(recommendationsIncludeAdult = includeAdult), clock.instant())
        return Outcome.Success(Unit)
    }

    fun forget() {
        cached.value = emptyMap()
    }

    private fun bare(id: AnimeId) = AnimeDetails(id, null, null, null, null, emptyList(), null, null, emptyList(), emptyList())
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

    // While set, a request is answered only once it completes, as AniList answering late or never.
    @Volatile
    var holding: CompletableDeferred<Unit>? = null

    // Holds every request from now on, or answers the ones held.
    @Synchronized
    fun hold(held: Boolean) {
        val was = holding
        if (held) {
            if (was == null) holding = CompletableDeferred()
        } else if (was != null) {
            holding = null
            was.complete(Unit)
        }
    }

    override suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>> {
        received += titles.mapTo(mutableSetOf()) { it.id }
        holding?.await()
        val error = server.load(needsSignIn = false) ?: failure
        return titles.associate { anime ->
            anime.id to (error?.let { Outcome.Failure(it) } ?: Outcome.Success(schedules[anime.id]?.let { FetchedAiring(it) }))
        }
    }
}

internal class DemoSearchRepository(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
    private val signIn: MutableStateFlow<SignInId?>,
) : SearchRepository {
    override suspend fun search(
        query: String,
        offset: Int,
        includeAdult: Boolean,
    ): Outcome<Page<Anime>> {
        server.load(needsSignIn = false)?.let { return Outcome.Failure(it) }
        val text = query.trim()
        if (text.isEmpty() || offset < 0) return Outcome.Failure(DataError.InvalidRequest)
        val found =
            fixtures.anime.filter { anime ->
                with(anime.titles) { (listOfNotNull(romaji, english, japanese) + synonyms).any { it.contains(text, ignoreCase = true) } }
            }
        return Outcome.Success(found.page(offset))
    }

    override fun openBrowse(list: BrowseList): BrowseSession =
        BrowseSession { offset, _ ->
            server.load(needsSignIn = false)?.let { return@BrowseSession Outcome.Failure(it) }
            if (offset < 0) return@BrowseSession Outcome.Failure(DataError.InvalidRequest)
            val ranked =
                when (list) {
                    BrowseList.Suggestions -> return@BrowseSession Outcome.Failure(DataError.SessionExpired)
                    BrowseList.ThisSeason ->
                        fixtures.anime.filter { it.airingStatus != AiringStatus.Finished }.map { Ranked(null, it) }
                    BrowseList.TopAiring ->
                        byRank(fixtures.anime.filter { it.airingStatus.effective == AiringStatus.Airing })
                            .mapIndexed { index, it -> Ranked(index + 1, it) }
                    BrowseList.TopRated -> byRank(fixtures.anime).mapIndexed { index, anime -> Ranked(index + 1, anime) }
                    BrowseList.MostPopular ->
                        fixtures.anime
                            .sortedByDescending { fixtures.details[it.id]?.statistics?.members ?: 0 }
                            .mapIndexed { index, anime -> Ranked(index + 1, anime) }
                }
            Outcome.Success(ranked.page(offset))
        }

    override fun openSuggestions(owner: SignInId): BrowseSession =
        BrowseSession { offset, _ ->
            if (offset < 0) return@BrowseSession Outcome.Failure(DataError.InvalidRequest)
            server.load(needsSignIn = true)?.let { return@BrowseSession Outcome.Failure(it) }
            if (signIn.value != owner) return@BrowseSession Outcome.Failure(DataError.ListEnded)
            Outcome.Success(fixtures.anime.map { Ranked(null, it) }.page(offset))
        }

    private fun byRank(anime: List<Anime>): List<Anime> = anime.filter { it.rank != null }.sortedBy { it.rank }

    private fun <T> List<T>.page(offset: Int): Page<T> {
        val items = drop(offset).take(PAGE_SIZE)
        val next = offset + items.size
        return Page(items, next.takeIf { it < size })
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

// The fixtures' user, as if read when the demo started and again at each refresh.
internal class DemoOwnProfile(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
    private val signIn: MutableStateFlow<SignInId?>,
    private val clock: Clock,
) : OwnProfile {
    private val fetchedAt = MutableStateFlow(clock.instant())

    override fun observeOwnProfile(): Flow<UserProfile?> = signIn.map { if (it != null) fixtures.user else null }

    override fun observeOwnProfileFetchedAt(): Flow<Instant?> = combine(signIn, fetchedAt) { signIn, at -> at.takeIf { signIn != null } }

    override suspend fun refreshOwnProfile(expectedOwner: SignInId?): Outcome<Unit> {
        if (expectedOwner != null && signIn.value != expectedOwner) return Outcome.Failure(DataError.ListEnded)
        val answer = server.load(needsSignIn = true).toOutcome()
        if (expectedOwner != null && signIn.value != expectedOwner) return Outcome.Failure(DataError.ListEnded)
        if (answer is Outcome.Success) fetchedAt.value = clock.instant()
        return answer
    }
}

// The demo's other users: one whose public list has several thousand entries, one whose list is private, and nobody else.
class DemoUserLists internal constructor(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
) : RemoteUserLists {
    private val received = CopyOnWriteArrayList<String>()

    // The name of each request received, answered or not: one a part.
    val requests: List<String> get() = received.toList()

    // As MAL's answer: it names no total and says nothing of the user.
    override suspend fun read(
        name: String,
        part: suspend (UserListPart) -> Unit,
    ): Outcome<Unit> {
        var from = 0
        while (true) {
            received += name
            val error =
                server.load(needsSignIn = false)
                    ?: DataError.PrivateList.takeIf { sameUser(name, fixtures.privateUserName) }
                    ?: DataError.UnknownUser.takeIf { !sameUser(name, fixtures.otherUserName) }
            if (error != null) return Outcome.Failure(error)
            val list = fixtures.otherUserList
            val to = minOf(from + PART_SIZE, list.size)
            part(UserListPart(list.subList(from, to)))
            if (to == list.size) return Outcome.Success(Unit)
            from = to
        }
    }

    override fun sameUser(
        a: String,
        b: String,
    ): Boolean = a.equals(b, ignoreCase = true)

    companion object {
        const val PART_SIZE = 300
    }
}

// The demo's score distributions: the fixtures', answered after the demo's latency, and not while it is offline.
class DemoScoreDistributions internal constructor(
    private val fixtures: FixtureSet,
    private val server: DemoServer,
) : ScoreDistributionSource {
    private val received = CopyOnWriteArrayList<AnimeId>()

    // The title of each request received, answered or not.
    val requests: List<AnimeId> get() = received.toList()

    // Every request fails so, as the service alone failing.
    @Volatile
    var failure: DataError? = null

    // While set, a request is answered only once it completes, as the service answering late.
    @Volatile
    var holding: CompletableDeferred<Unit>? = null

    override suspend fun distribution(id: AnimeId): Outcome<ScoreDistribution?> {
        received += id
        holding?.await()
        delay(server.latency)
        if (server.offline.value) return Outcome.Failure(DataError.NoConnection)
        failure?.let { return Outcome.Failure(it) }
        return Outcome.Success(fixtures.scores[id])
    }
}

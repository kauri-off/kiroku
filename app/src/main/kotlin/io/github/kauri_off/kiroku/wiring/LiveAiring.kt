package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.AiringSource
import io.github.kauri_off.kiroku.core.airing.AiringStore
import io.github.kauri_off.kiroku.core.airing.AiringTime
import io.github.kauri_off.kiroku.core.airing.FetchedAiring
import io.github.kauri_off.kiroku.core.airing.ListAiring
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.data.db.RoomAiringStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

// What the airing of a live source is built from: its source of episode times, its store and its list.
internal typealias AiringOf = (parts: LiveParts, source: AiringSource, store: AiringStore, list: ListSource) -> AiringRepository

// The calculator's results over the list, the database's airing data and AniList, on the device's clock and its changes.
internal fun listAiring(
    parts: LiveParts,
    source: AiringSource,
    store: AiringStore,
    list: ListSource,
): AiringRepository = ListAiring(source, store, list, AiringTime(parts.clock, parts.clockSet), parts.scope, parts.unexpected)

// The airing of a live source over its own database and the process's AniList client, built at its first call, off the main thread.
internal class LiveAiring(
    private val parts: LiveParts,
    session: Flow<Session>,
    list: () -> ListSource,
    guard: AiringWriteGuard,
    build: AiringOf = ::listAiring,
) : AiringRepository {
    private val store =
        lazy {
            val room = RoomAiringStore(parts.database, Dispatchers.IO, parts.unexpected)
            SignedInStore(room, session, guard, room::clear)
        }
    private val built = lazy { build(parts, SignedInSource(parts.aniList, session), store.value, list()) }
    private val opening = Mutex()

    @Volatile
    private var opened = false

    // S9: the store is emptied at its first use while nobody is signed in, for a sign-out the process did not finish.
    private suspend fun store(): SignedInStore {
        val store = store.onFirstUse(parts.scope)
        if (!opened) {
            opening.withLock {
                if (!opened) reported { store.forget() }
                opened = true
            }
        }
        return store
    }

    private suspend fun airing(): AiringRepository {
        store()
        return built.onFirstUse(parts.scope)
    }

    override fun observeAiring(): Flow<Map<AnimeId, Airing>> = flow { emitAll(airing().observeAiring()) }

    override fun observePageAiring(page: Anime): Flow<Map<AnimeId, Airing>> = flow { emitAll(airing().observePageAiring(page)) }

    override fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> = flow { emitAll(airing().observeSchedules()) }

    override fun observeStoredSchedules(): Flow<Map<AnimeId, AiringSchedule>> = flow { emitAll(airing().observeStoredSchedules()) }

    override suspend fun refreshStale(): Outcome<Unit> = airing().refreshStale()

    override suspend fun refresh(): Outcome<Unit> = airing().refresh()

    override suspend fun refreshStale(page: Anime): Outcome<Unit> = airing().refreshStale(page)

    override suspend fun refresh(page: Anime): Outcome<Unit> = airing().refresh(page)

    override suspend fun refresh(titles: Set<AnimeId>): Outcome<Unit> = airing().refresh(titles)

    // S9, after the session is signed out; a store that fails it is emptied at the first use of the next process.
    suspend fun forget() = reported { store().forget() }

    private suspend fun reported(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            parts.unexpected(e)
        }
    }
}

// Asks nothing while nobody is signed in, and a call under way at a sign-out ends there, every title of it failed.
internal class SignedInSource(
    private val source: AiringSource,
    private val session: Flow<Session>,
) : AiringSource {
    override suspend fun isCurrent(owner: SignInId?): Boolean = session.first().owner == owner

    override suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>> = fetch(titles, since, emptySet()) {}

    override suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
        pulled: Set<AnimeId>,
        whole: suspend (Map<AnimeId, Outcome<FetchedAiring?>>) -> Unit,
    ): Map<AnimeId, Outcome<FetchedAiring?>> = fetchAnswers(session.first().owner, titles, since, pulled, coming = false, whole)

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun fetchAnswers(
        owner: SignInId?,
        titles: Collection<Anime>,
        since: Instant,
        pulled: Set<AnimeId>,
        coming: Boolean,
        whole: suspend (Map<AnimeId, Outcome<FetchedAiring?>>) -> Unit,
    ): Map<AnimeId, Outcome<FetchedAiring?>> =
        session
            .map { it.owner }
            .distinctUntilChanged()
            .transformLatest { current ->
                if (owner != null && current == owner) {
                    emit(source.fetch(titles, since, pulled) { if (!coming) whole(it) })
                } else {
                    emit(null)
                }
            }.first() ?: titles.associate { it.id to Outcome.Failure(DataError.ListEnded) }
}

internal typealias AiringWriteGuard = suspend (SignInId?, suspend () -> Boolean) -> Boolean

// Stores nothing while nobody is signed in; forgetting goes on.
internal class SignedInStore(
    private val store: AiringStore,
    private val session: Flow<Session>,
    private val guard: AiringWriteGuard,
    // Forgets every title, also one the store cannot read.
    private val clear: suspend () -> Unit,
) : AiringStore {
    // Held across each write's look at the session, so that nothing a sign-out has forgotten is written after it.
    private val writes = Mutex()

    override fun observe(): Flow<Map<AnimeId, StoredAiring>> = store.observe()

    override suspend fun read(): Map<AnimeId, StoredAiring> = store.read()

    override suspend fun write(titles: Map<AnimeId, StoredAiring?>) {
        writeAnswers(titles.filterValues { it == null }, session.first().owner)
    }

    override suspend fun writeAnswers(
        titles: Map<AnimeId, StoredAiring?>,
        owner: SignInId?,
    ): Boolean =
        guard(owner) {
            writes.withLock {
                if (session.first().owner != owner || (owner == null && titles.values.any { it != null })) return@withLock false
                if (titles.isNotEmpty()) store.write(titles)
                true
            }
        }

    // Empties the store while nobody is signed in, and only then.
    suspend fun forget() {
        writes.withLock { if (session.first() == Session.SignedOut) clear() }
    }
}

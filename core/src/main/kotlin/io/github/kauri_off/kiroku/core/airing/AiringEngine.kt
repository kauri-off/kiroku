package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.tracked
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.airingStale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

// Airing data over a store and a source of episode times: the rules of what is asked and what is kept (section 4).
class AiringEngine(
    private val source: AiringSource,
    store: AiringStore,
    // Where the tracked titles are read; nothing here syncs or writes it.
    private val list: ListSource,
    private val clock: Clock,
    private val scope: CoroutineScope,
    // Hears of what nobody expected: the source's and the engine's own exceptions, and the first of a store's streak.
    private val unexpected: (Throwable) -> Unit,
) {
    // A store call that threw; it fails the one call the engine was asked in as a storage error.
    private class StoreFailure(
        cause: Throwable,
    ) : Exception(cause)

    // The list as airing reads it: the tracked titles, and those in a tracked status that MAL has marked finished.
    private data class Tracking(
        val tracked: Set<AnimeId>,
        val finished: Set<AnimeId>,
    )

    private val local =
        object : AiringStore {
            override fun observe() =
                store.observe().onEach { unreadable.set(false) }.catch { throw if (it is Exception) storeFailure(unreadable, it) else it }

            override suspend fun read() = storeCall(unreadable) { store.read() }

            override suspend fun write(titles: Map<AnimeId, StoredAiring?>) = storeCall(unwritable) { store.write(titles) }
        }

    // Whether the store's last read and its last write failed: only the first failure of a streak is reported.
    private val unreadable = AtomicBoolean()
    private val unwritable = AtomicBoolean()

    // Held for every change to the store and to the fields below it, never while the source is asked.
    private val mutex = Mutex()

    // The list the store was last made to agree with.
    private var settled: Tracking? = null

    // The titles asked for and not answered yet, of which [waiting] are not on their way to the source.
    private val asked = mutableMapOf<AnimeId, CompletableDeferred<DataError?>>()
    private val waiting = LinkedHashSet<AnimeId>()

    // Of the titles asked for, those a page asked for.
    private val forPages = mutableSetOf<AnimeId>()
    private var stopped = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // A12: what was fetched for the pages of titles outside the tracked set, which no store sees.
    private val pages = MutableStateFlow(emptyMap<AnimeId, AiringSchedule>())

    private val follows = Any()
    private var following: Job? = null

    init {
        check(scope.isActive) { STOPPED }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (signal in wake) {
                    while (true) {
                        val ids = mutex.withLock { waiting.toSet().also { waiting.clear() } }
                        if (ids.isEmpty()) break
                        answer(ids)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        stopped = true
                        asked.values.forEach { it.cancel() }
                        asked.clear()
                        waiting.clear()
                    }
                }
            }
        }
        follow()
    }

    // The schedules of the stored titles, after every change to one; a title the source does not know has none.
    fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> =
        shown {
            local.observe().map { stored -> stored.mapNotNull { (id, airing) -> airing.schedule?.let { id to it } }.toMap() }
        }

    // One title's schedule: the stored one, else the one fetched for its page (A12).
    fun observeSchedule(id: AnimeId): Flow<AiringSchedule?> =
        shown {
            val stored = local.observe().map { it[id] }.distinctUntilChanged()
            combine(stored, pages.map { it[id] }.distinctUntilChanged()) { airing, page -> if (airing != null) airing.schedule else page }
        }

    // Asks for every tracked title, as a person's pull does, and returns once each is answered.
    suspend fun refresh(): Outcome<Unit> = ask { tracking, _ -> tracking.tracked }

    // Asks for the tracked titles whose data is stale; a list sync and a new observer do this by themselves.
    suspend fun refreshStale(): Outcome<Unit> =
        ask { tracking, stored ->
            val now = clock.instant()
            tracking.tracked.filter { airingStale(stored[it]?.fetchedAt, now) }
        }

    // For a title's page: a tracked title is refreshed; any other is fetched and held in memory (A12), unless finished.
    suspend fun refresh(anime: Anime): Outcome<Unit> =
        ask { tracking, _ ->
            when {
                anime.id in tracking.tracked -> listOf(anime.id)
                anime.airingStatus.effective == AiringStatus.Finished -> emptyList()
                else -> listOf(anime.id).also { forPages += anime.id }
            }
        }

    // The store's own flow, which a new observer makes fresh; it is tried a few times in a row.
    private fun <T> shown(stored: () -> Flow<T>): Flow<T> =
        flow {
            follow()
            scope.launch { guarded { refreshStale() } }
            var failed = 0
            emitAll(
                stored()
                    .onEach { failed = 0 }
                    .retryWhen { cause, _ -> (cause is StoreFailure && ++failed < STORE_ATTEMPTS).also { if (it) delay(STORE_RETRY) } }
                    .catch { throw if (it is StoreFailure) DataException(DataError.StorageError, it.cause) else it },
            )
        }.distinctUntilChanged()

    // A list that cannot be read is followed again at the engine's next use.
    private fun follow() {
        synchronized(follows) {
            if (following?.isActive == true) return
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        coroutineScope {
                            launch { list.observeEntries().collect { entries -> guarded { settle(tracking(entries)) } } }
                            launch { list.observeSyncedAt().filterNotNull().collect { guarded { refreshStale() } } }
                        }
                    } catch (_: DataException) {
                    }
                }
            following = job
            job.start()
        }
    }

    private suspend fun settle(tracking: Tracking) {
        try {
            mutex.withLock { if (tracking != settled) reconcile(tracking) }
        } catch (_: StoreFailure) {
        }
    }

    private fun tracking(entries: List<ListEntry>): Tracking {
        val (tracked, others) = entries.filter { it.status.tracked }.partition { it.tracked }
        return Tracking(tracked.mapTo(mutableSetOf()) { it.anime.id }, others.mapTo(mutableSetOf()) { it.anime.id })
    }

    // The store holds the tracked titles and A13's finales alone (S9); one new to the set is asked for at once (A11).
    private suspend fun reconcile(tracking: Tracking): Map<AnimeId, StoredAiring> {
        val now = clock.instant()
        val stored = local.read()
        val forgotten =
            stored.filterNot { (id, airing) -> id in tracking.tracked || (id in tracking.finished && airing.announceable(now)) }.keys
        if (forgotten.isNotEmpty()) local.write(forgotten.associateWith { null })
        val known = settled?.tracked.orEmpty()
        settled = tracking
        want(tracking.tracked.filter { it !in known && it !in stored })
        return stored - forgotten
    }

    // A13: the title's last episode can still be announced.
    private fun StoredAiring.announceable(now: Instant): Boolean {
        val last = schedule?.episodes?.maxOfOrNull { it.airsAt } ?: return false
        return now < last + finaleKeptFor.toJavaDuration()
    }

    // The list as it is now decides what is asked; the call returns when each of those titles is answered.
    private suspend fun ask(ids: suspend (Tracking, Map<AnimeId, StoredAiring>) -> Collection<AnimeId>): Outcome<Unit> {
        follow()
        val answers =
            try {
                mutex.withLock {
                    // Read under the lock, so that no list older than one already settled is settled after it.
                    val tracking = tracking(list.observeEntries().first())
                    want(ids(tracking, reconcile(tracking)))
                }
            } catch (e: DataException) {
                return Outcome.Failure(e.error)
            } catch (_: StoreFailure) {
                return Outcome.Failure(DataError.StorageError)
            } catch (e: Exception) {
                if (e is CancellationException || !scope.isActive) throw e
                unexpected(e)
                return Outcome.Failure(DataError.ServerError)
            }
        return try {
            answers.awaitAll().firstNotNullOfOrNull { it }?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException(STOPPED, e)
        }
    }

    // A title already asked for shares that answer, so no title is asked for twice at once.
    private fun want(ids: Collection<AnimeId>): List<Deferred<DataError?>> {
        check(!stopped) { STOPPED }
        val answers =
            ids.map { id ->
                asked.getOrPut(id) {
                    waiting += id
                    CompletableDeferred()
                }
            }
        if (waiting.isNotEmpty()) wake.trySend(Unit)
        return answers
    }

    // One call to the source. A10: a title is stored only when its answer is whole, and any other stays as it was.
    private suspend fun answer(ids: Set<AnimeId>) {
        val errors =
            try {
                val began = clock.instant()
                keep(ids, fetch(ids), began)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpected(e)
                ids.associateWith { DataError.ServerError }
            }
        mutex.withLock {
            forPages -= ids
            for (id in ids) asked.remove(id)?.complete(errors[id])
        }
    }

    // The source throwing fails every title of the call: an IOException as no connection, anything else reported.
    private suspend fun fetch(ids: Set<AnimeId>): Map<AnimeId, Outcome<FetchedAiring?>> =
        try {
            source.fetch(ids)
        } catch (e: Exception) {
            ended(e)
            val error = if (e is IOException) DataError.NoConnection else DataError.ServerError.also { unexpected(e) }
            ids.associateWith { Outcome.Failure(error) }
        }

    // An answer goes to the store for a title tracked when it arrives, to memory for one a page asked for, else nowhere.
    private suspend fun keep(
        ids: Set<AnimeId>,
        answers: Map<AnimeId, Outcome<FetchedAiring?>>,
        began: Instant,
    ): Map<AnimeId, DataError?> {
        val errors = mutableMapOf<AnimeId, DataError?>()
        val fetched = mutableMapOf<AnimeId, FetchedAiring?>()
        for (id in ids) {
            when (val answer = answers[id]) {
                null -> errors[id] = DataError.ServerError
                is Outcome.Failure -> errors[id] = answer.error
                is Outcome.Success -> fetched[id] = answer.value
            }
        }
        if (ids.any { it !in answers }) unexpected(IllegalStateException(UNANSWERED))
        mutex.withLock {
            val tracked = settled?.tracked.orEmpty()
            val stored = fetched.filterKeys { it in tracked }
            var held = pages.value
            for ((id, airing) in fetched) {
                if (id in tracked || id !in forPages) continue
                held = if (airing == null) held - id else held + (id to airing.over(held[id]))
            }
            if (stored.isNotEmpty()) {
                try {
                    val before = local.read()
                    local.write(stored.mapValues { (id, airing) -> StoredAiring(airing?.over(before[id]?.schedule), began) })
                    held -= stored.keys
                } catch (_: StoreFailure) {
                    for (id in stored.keys) errors[id] = DataError.StorageError
                }
            }
            pages.value = held
        }
        return errors
    }

    // A9: an episode is its number. The answer's episodes, each at its first time, and the earlier ones it left out.
    private fun FetchedAiring.over(stored: AiringSchedule?): AiringSchedule {
        val given = schedule.episodes.groupBy { it.number }.map { (_, same) -> same.minBy { it.airsAt } }
        val numbers = given.mapTo(mutableSetOf()) { it.number }
        val kept = completeFrom?.let { from -> stored?.episodes?.filter { it.airsAt < from && it.number !in numbers } }.orEmpty()
        return schedule.copy(episodes = (kept + given).sortedBy { it.number })
    }

    // The engine's own exception fails what it was doing, and nothing else.
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (scope.isActive) unexpected(e)
        }
    }

    private suspend inline fun <T> storeCall(
        failing: AtomicBoolean,
        call: () -> T,
    ): T =
        try {
            call().also { failing.set(false) }
        } catch (e: Exception) {
            throw storeFailure(failing, e)
        }

    private suspend fun storeFailure(
        failing: AtomicBoolean,
        e: Exception,
    ): Exception {
        ended(e)
        if (failing.compareAndSet(false, true)) unexpected(e)
        return StoreFailure(e)
    }

    // A cancellation of the caller or of the engine's scope ends the call; any other is a failure like any exception.
    private suspend fun ended(e: Exception) {
        if (e !is CancellationException) return
        currentCoroutineContext().ensureActive()
        if (!scope.isActive) throw e
    }

    private companion object {
        const val STOPPED = "The airing engine's scope has ended"
        const val UNANSWERED = "The airing source left a title it was asked for unanswered"
        const val STORE_ATTEMPTS = 3
        val STORE_RETRY = 1.seconds
    }
}

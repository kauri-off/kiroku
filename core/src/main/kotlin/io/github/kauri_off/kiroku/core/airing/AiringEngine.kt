package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.error.StoreCalls
import io.github.kauri_off.kiroku.core.error.StoreFailure
import io.github.kauri_off.kiroku.core.error.endedCall
import io.github.kauri_off.kiroku.core.error.retriedOverStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    // The list as airing reads it: the tracked titles, and those in a tracked status that MAL has marked finished.
    private data class Tracking(
        val tracked: Map<AnimeId, Anime>,
        val finished: Set<AnimeId>,
    )

    private val stores = StoreCalls(scope, unexpected)

    private val local =
        object : AiringStore {
            override fun observe() = stores.observing(store.observe())

            override suspend fun read() = stores.reading { store.read() }

            override suspend fun write(titles: Map<AnimeId, StoredAiring?>) = stores.writing { store.write(titles) }
        }

    // Held for every change to the store and to the fields below it, never while the source is asked.
    private val mutex = Mutex()

    // The list as last read, which decides what an answer may store, and whether the store has yet to agree with it.
    private var settled: Tracking? = null
    private var owed = false

    // The titles that entered the tracked set and were not looked up in the store yet.
    private val arrived = mutableSetOf<AnimeId>()

    // The titles asked for and not answered yet, of which [waiting] are not on their way to the source.
    private val asked = mutableMapOf<AnimeId, CompletableDeferred<DataError?>>()
    private val waiting = LinkedHashMap<AnimeId, Anime>()

    // Of the titles asked for, those a page asked for.
    private val forPages = mutableSetOf<AnimeId>()
    private var stopped = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // A12: what was fetched for the pages of titles outside the tracked set, which no store sees, and when each was asked for.
    private val pages = MutableStateFlow(emptyMap<AnimeId, AiringSchedule>())
    private val pagesAsked = mutableMapOf<AnimeId, Instant>()

    private val follows = Any()
    private var following: Job? = null

    init {
        check(scope.isActive) { STOPPED }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (signal in wake) {
                    while (true) {
                        val titles = mutex.withLock { waiting.toMap().also { waiting.clear() } }
                        if (titles.isEmpty()) break
                        answer(titles)
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
    fun observeStored(): Flow<Map<AnimeId, AiringSchedule>> =
        read {
            local.observe().map { stored -> stored.mapNotNull { (id, airing) -> airing.schedule?.let { id to it } }.toMap() }
        }

    // The same without a schedule whose numbering the source doubts: all that a notification may read.
    fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> =
        observeStored().map { stored -> stored.filterValues { it.doubtedOver == null } }.distinctUntilChanged()

    // For a title's page alone, never for a notification: the stored schedule, else the one fetched for the page (A12).
    fun observePageSchedule(id: AnimeId): Flow<AiringSchedule?> =
        read {
            val stored = local.observe().map { it[id] }.distinctUntilChanged()
            combine(stored, pages.map { it[id] }.distinctUntilChanged()) { airing, page -> if (airing != null) airing.schedule else page }
        }

    // Asks for every tracked title, as a person's pull does, and returns once each is answered.
    suspend fun refresh(): Outcome<Unit> = ask { tracking, _ -> tracking.tracked.values }

    // Asks for the tracked titles whose data is stale, as a shown screen does; the end of a list sync does it by itself.
    suspend fun refreshStale(): Outcome<Unit> =
        ask { tracking, stored ->
            val now = clock.instant()
            tracking.tracked.values.filter { airingStale(stored[it.id]?.fetchedAt, now) }
        }

    // A pull on a title's page: a tracked title is refreshed; any other is fetched and held in memory (A12), unless finished.
    suspend fun refresh(anime: Anime): Outcome<Unit> = ask { tracking, _ -> listOfNotNull(pageTitle(tracking, anime)) }

    // N3: asks for those of [titles] that are tracked and for no other, which a finished title is not.
    suspend fun refresh(titles: Set<AnimeId>): Outcome<Unit> = ask { tracking, _ -> titles.mapNotNull(tracking.tracked::get) }

    // A title's page that is shown: the same, only when what is held of the title is stale.
    suspend fun refreshStale(anime: Anime): Outcome<Unit> =
        ask { tracking, stored ->
            val asked = if (anime.id in tracking.tracked) stored[anime.id]?.fetchedAt else pagesAsked[anime.id]
            if (airingStale(asked, clock.instant())) listOfNotNull(pageTitle(tracking, anime)) else emptyList()
        }

    // The title a page asks for: as the list has it when tracked, none when finished, else its own, marked as a page's.
    private fun pageTitle(
        tracking: Tracking,
        anime: Anime,
    ): Anime? {
        tracking.tracked[anime.id]?.let { return it }
        if (anime.airingStatus.effective == AiringStatus.Finished) return null
        forPages += anime.id
        return anime
    }

    // The store's own flow. Reading asks the source nothing: only a refresh and a change of the list do.
    private fun <T> read(stored: () -> Flow<T>): Flow<T> =
        flow {
            follow()
            emitAll(stored().retriedOverStore())
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
                            // A sync that ends while the list is followed; the time of an earlier one says nothing new.
                            launch {
                                list
                                    .observeSyncedAt()
                                    .distinctUntilChanged()
                                    .drop(1)
                                    .filterNotNull()
                                    .collect { guarded { refreshStale() } }
                            }
                        }
                    } catch (_: DataException) {
                    }
                }
            following = job
            job.start()
        }
    }

    // A store that fails is asked a few times in a row, then again at the engine's next use.
    private suspend fun settle(tracking: Tracking) {
        repeat(StoreCalls.ATTEMPTS) { attempt ->
            if (attempt > 0) delay(StoreCalls.RETRY)
            try {
                mutex.withLock { if (tracking != settled || owed) reconcile(tracking) }
                return
            } catch (_: StoreFailure) {
            }
        }
    }

    private fun tracking(entries: List<ListEntry>): Tracking {
        val (tracked, others) = entries.filter { it.status.tracked }.partition { it.tracked }
        return Tracking(tracked.associate { it.anime.id to it.anime }, others.mapTo(mutableSetOf()) { it.anime.id })
    }

    // The store holds the tracked titles and A13's finales alone (S9); one new to the set is asked for at once (A11).
    private suspend fun reconcile(tracking: Tracking): Map<AnimeId, StoredAiring> {
        // The list as first read enters nothing: what is not stored of it is stale, which a refresh asks for.
        settled?.let { arrived += tracking.tracked.keys - it.tracked.keys }
        arrived.retainAll(tracking.tracked.keys)
        settled = tracking
        owed = true
        val now = clock.instant()
        val stored = local.read()
        want(arrived.filter { it !in stored }.map(tracking.tracked::getValue))
        arrived.clear()
        val forgotten =
            stored.filterNot { (id, airing) -> id in tracking.tracked || (id in tracking.finished && airing.announceable(now)) }.keys
        if (forgotten.isNotEmpty()) local.write(forgotten.associateWith { null })
        owed = false
        return stored - forgotten
    }

    // A13: the title's last episode can still be announced.
    private fun StoredAiring.announceable(now: Instant): Boolean {
        val last = schedule?.episodes?.maxOfOrNull { it.airsAt } ?: return false
        return now < last + announceableFor.toJavaDuration()
    }

    // The list as it is now decides what is asked; the call returns when each of those titles is answered.
    private suspend fun ask(titles: suspend (Tracking, Map<AnimeId, StoredAiring>) -> Collection<Anime>): Outcome<Unit> {
        follow()
        val answers =
            try {
                mutex.withLock {
                    // Read under the lock, so that no list older than one already settled is settled after it.
                    val tracking = tracking(list.observeEntries().first())
                    want(titles(tracking, reconcile(tracking)))
                }
            } catch (e: DataException) {
                return Outcome.Failure(e.error)
            } catch (_: StoreFailure) {
                return Outcome.Failure(DataError.StorageError)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!scope.isActive) scope.endedCall(e)
                unexpected(e)
                return Outcome.Failure(DataError.ServerError)
            }
        return try {
            answers.awaitAll().firstNotNullOfOrNull { it }?.let { Outcome.Failure(it) } ?: Outcome.Success(Unit)
        } catch (e: CancellationException) {
            scope.endedCall(IllegalStateException(STOPPED, e))
        }
    }

    // A title already asked for shares that answer, so no title is asked for twice at once.
    private fun want(titles: Collection<Anime>): List<Deferred<DataError?>> {
        check(!stopped) { STOPPED }
        val answers =
            titles.map { anime ->
                asked.getOrPut(anime.id) {
                    waiting[anime.id] = anime
                    CompletableDeferred()
                }
            }
        if (waiting.isNotEmpty()) wake.trySend(Unit)
        return answers
    }

    // One call to the source. A10: a title is stored only when its answer is whole, and any other stays as it was.
    private suspend fun answer(titles: Map<AnimeId, Anime>) {
        val errors =
            try {
                val began = clock.instant()
                // A9: what a notification can still need, so an episode moved earlier is not lost; in whole seconds, rounded down.
                val since = (began - announceableFor.toJavaDuration()).truncatedTo(ChronoUnit.SECONDS)
                keep(titles.keys, fetch(titles, since), began, since)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpected(e)
                titles.keys.associateWith { DataError.ServerError }
            }
        mutex.withLock {
            forPages -= titles.keys
            for (id in titles.keys) asked.remove(id)?.complete(errors[id])
        }
    }

    // The source throwing fails every title of the call: an IOException as no connection, anything else reported.
    private suspend fun fetch(
        titles: Map<AnimeId, Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>> =
        try {
            source.fetch(titles.values, since)
        } catch (e: Exception) {
            stores.ended(e)
            val error = if (e is IOException) DataError.NoConnection else DataError.ServerError.also { unexpected(e) }
            titles.keys.associateWith { Outcome.Failure(error) }
        }

    // An answer goes to the store for a title the list tracks when it arrives, to memory for a page's, else nowhere.
    private suspend fun keep(
        ids: Set<AnimeId>,
        answers: Map<AnimeId, Outcome<FetchedAiring?>>,
        began: Instant,
        since: Instant,
    ): Map<AnimeId, DataError?> {
        val errors = mutableMapOf<AnimeId, DataError?>()
        val fetched = mutableMapOf<AnimeId, FetchedAiring?>()
        var broken: String? = null
        for (id in ids) {
            val answer = answers[id]
            val from = (answer as? Outcome.Success)?.value?.completeFrom
            when {
                answer == null -> errors[id] = DataError.ServerError.also { broken = UNANSWERED }
                answer is Outcome.Failure -> errors[id] = answer.error
                from != null && from > since -> errors[id] = DataError.ServerError.also { broken = LEFT_OUT }
                answer is Outcome.Success -> fetched[id] = answer.value
            }
        }
        broken?.let { unexpected(IllegalStateException(it)) }
        mutex.withLock {
            val tracked = settled?.tracked.orEmpty()
            val stored = fetched.filterKeys { it in tracked }
            var held = pages.value
            for ((id, airing) in fetched) {
                if (id in tracked || id !in forPages) continue
                held = if (airing == null) held - id else held + (id to airing.over(held[id]))
                pagesAsked[id] = began
            }
            if (stored.isNotEmpty()) {
                try {
                    val before = local.read()
                    local.write(stored.mapValues { (id, airing) -> StoredAiring(airing?.over(before[id]?.schedule), began) })
                    held -= stored.keys
                    pagesAsked -= stored.keys
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

    private companion object {
        const val STOPPED = "The airing engine's scope has ended"
        const val UNANSWERED = "The airing source left a title it was asked for unanswered"
        const val LEFT_OUT = "The airing source left out episodes from after the instant it was asked for"
    }
}

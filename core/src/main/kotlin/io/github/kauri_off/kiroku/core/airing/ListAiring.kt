package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update

// Airing for every screen: the calculator's results over the list and the stored data, once for all observers, on one tick (A1, A2).
class ListAiring(
    source: AiringSource,
    store: AiringStore,
    list: ListSource,
    time: AiringTime,
    scope: CoroutineScope,
    // Hears of what nobody expected, as the engine's does.
    private val unexpected: (Throwable) -> Unit,
) : AiringRepository {
    private val engine = AiringEngine(source, store, list, time.clock, scope, unexpected)

    // A page that is observed, by how many.
    private data class Watched(
        val anime: Anime,
        val observers: Int,
    )

    private data class Key(
        val id: AnimeId,
        val page: Boolean,
    )

    private class Asked(
        val titles: Map<Key, AiringOf>,
        val pages: Set<AnimeId>,
    )

    private sealed interface Read

    private class Results(
        val of: Asked,
        val list: Map<AnimeId, Airing>,
        val pages: Map<AnimeId, Airing>,
    ) : Read

    private class Failed(
        val error: DataError,
        val cause: Throwable?,
    ) : Read

    private val watched = MutableStateFlow(emptyMap<AnimeId, Watched>())

    // The titles of the list that can still air.
    private val unfinished: Flow<Map<AnimeId, Anime>> =
        list
            .observeEntries()
            .map { entries -> entries.map { it.anime }.filter { it.airingStatus.effective != AiringStatus.Finished }.associateBy { it.id } }
            .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val pageSchedules: Flow<Map<AnimeId, AiringSchedule?>> =
        watched.map { it.keys }.distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> engine.observePageSchedule(id).map { id to it } }) { it.toMap() }
            }
        }

    // A12: a page's title is computed apart from the list's only where the page knows another schedule, or the list has none of it.
    private val asked: Flow<Asked> =
        combine(unfinished, engine.observeSchedules(), watched, pageSchedules) { titles, stored, watched, pageSchedules ->
            val ofList = titles.mapValues { (id, anime) -> AiringOf(anime, stored[id]) }
            val pages = watched.keys intersect pageSchedules.keys
            val ofPages =
                pages
                    .associateWith { id -> AiringOf(titles[id] ?: watched.getValue(id).anime, pageSchedules[id]) }
                    .filter { (id, of) -> of != ofList[id] }
            Asked(ofList.mapKeys { Key(it.key, page = false) } + ofPages.mapKeys { Key(it.key, page = true) }, pages)
        }

    private val read: Flow<Read> =
        asked
            .onTick(time, unexpected) { it.titles }
            .map<_, Read> { (asked, results) ->
                val ofList = results.filterKeys { !it.page }.mapKeys { it.key.id }
                val ofPages = asked.pages.mapNotNull { id -> (results[Key(id, page = true)] ?: ofList[id])?.let { id to it } }
                Results(asked, ofList, ofPages.toMap())
            }.catch { cause ->
                if (cause !is DataException && cause !is CancellationException) unexpected(cause)
                emit(Failed((cause as? DataException)?.error ?: DataError.ServerError, cause.cause))
            }.shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

    private val results: Flow<Results> =
        read.map {
            when (it) {
                is Results -> it
                is Failed -> throw DataException(it.error, it.cause)
            }
        }

    override fun observeAiring(): Flow<Map<AnimeId, Airing>> = results.map { it.list }.distinctUntilChanged()

    override fun observePageAiring(page: Anime): Flow<Map<AnimeId, Airing>> =
        flow {
            watched.update { it + (page.id to Watched(page, (it[page.id]?.observers ?: 0) + 1)) }
            try {
                emitAll(
                    results
                        .mapNotNull { results ->
                            val own = results.pages[page.id]
                            when {
                                page.id !in results.of.pages -> null
                                own == null || results.list[page.id] === own -> results.list
                                else -> results.list + (page.id to own)
                            }
                        }.distinctUntilChanged(),
                )
            } finally {
                watched.update {
                    val left = it.getValue(page.id).observers - 1
                    if (left == 0) it - page.id else it + (page.id to it.getValue(page.id).copy(observers = left))
                }
            }
        }

    override fun observeSchedules(): Flow<Map<AnimeId, AiringSchedule>> = engine.observeSchedules()

    override suspend fun refreshStale(): Outcome<Unit> = engine.refreshStale().also { given() }

    override suspend fun refresh(): Outcome<Unit> = engine.refresh().also { given() }

    override suspend fun refreshStale(page: Anime): Outcome<Unit> = engine.refreshStale(page).also { given() }

    override suspend fun refresh(page: Anime): Outcome<Unit> = engine.refresh(page).also { given() }

    // A refresh returns once the observers are given the list and the data as they are after it, so a read that follows holds them.
    private suspend fun given() {
        try {
            read.first { it !is Results || it.of.titles == asked.first().titles }
        } catch (_: DataException) {
        }
    }
}

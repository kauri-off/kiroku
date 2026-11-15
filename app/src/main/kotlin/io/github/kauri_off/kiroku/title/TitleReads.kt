package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

// The list entries of [ids] alone; a change to another title's entry is not heard.
fun ListSource.entriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>> =
    if (ids.isEmpty()) {
        flowOf(emptyMap())
    } else {
        combine(ids.map(::observeEntry)) { entries -> entries.filterNotNull().associateBy { it.anime.id } }.distinctUntilChanged()
    }

// The airing of [ids] alone.
fun AiringRepository.airingOf(ids: Set<AnimeId>): Flow<Map<AnimeId, Airing>> =
    observeAiring().map { airing -> airing.filterKeys(ids::contains) }.distinctUntilChanged()

// The titles a page about [id] shows: the title and its related titles.
fun shownTitles(
    id: AnimeId,
    details: AnimeDetails?,
): Set<AnimeId> = setOf(id) + details?.related.orEmpty().map { it.anime.id }

// Each value with what [read] gives for the value's key. [read] starts again only when the key changes, and a value
// is emitted only with what was read for its own key, so the two never disagree.
@OptIn(ExperimentalCoroutinesApi::class)
fun <T, K, R> Flow<T>.withRead(
    key: (T) -> K,
    read: (K) -> Flow<R>,
): Flow<Pair<T, R>> =
    channelFlow {
        val values = this@withRead.shareIn(this, SharingStarted.Eagerly, replay = 1)
        values
            .map(key)
            .distinctUntilChanged()
            .flatMapLatest { k -> combine(values.filter { key(it) == k }, read(k), ::Pair) }
            .collect { send(it) }
    }

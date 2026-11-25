package io.github.kauri_off.kiroku.title

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.chainWalk
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

// The list entries of [ids] alone, in one read; a change to another title's entry is not heard.
fun ListSource.entriesOf(
    ids: Set<AnimeId>,
    owner: SignInId? = null,
): Flow<Map<AnimeId, ListEntry>> =
    when {
        ids.isEmpty() -> flowOf(emptyMap())
        owner == null -> observeEntriesOf(ids).distinctUntilChanged()
        else ->
            observeOwnedEntriesOf(ids)
                .map { owned ->
                    if (owned.owner != owner) emptyMap() else owned.entries.associateBy { it.anime.id }
                }.distinctUntilChanged()
    }

// The airing of [ids] alone, with [page]'s own result when given (A12); it ends when the list or the airing cannot be read.
fun AiringRepository.airingOf(
    ids: Set<AnimeId>,
    page: Anime? = null,
): Flow<Map<AnimeId, Airing>> =
    (
        if (page ==
            null
        ) {
            observeAiring()
        } else {
            observePageAiring(page)
        }
    ).map { airing -> airing.filterKeys(ids::contains) }.distinctUntilChanged()

// The titles a page about [id] shows: the title, its related titles and the titles of its chain of sequels.
fun shownTitles(
    id: AnimeId,
    details: AnimeDetails?,
    chainPages: Map<AnimeId, AnimeDetails> = emptyMap(),
): Set<AnimeId> = setOf(id) + details?.related.orEmpty().map { it.anime.id } + chainWalk(id, chainPages.related())

// The stored pages of the titles of the chain of sequels around [id], [id] included: read together, and read again
// whenever the chain the pages tell of differs from the titles read.
@OptIn(ExperimentalCoroutinesApi::class)
fun AnimeRepository.chainPagesOf(id: AnimeId): Flow<Map<AnimeId, AnimeDetails>> =
    channelFlow {
        val titles = MutableStateFlow(setOf(id))
        titles.flatMapLatest(::observeDetailsOf).collect { pages ->
            val chain = chainWalk(id, pages.related()).toSet()
            if (chain == titles.value) send(pages) else titles.value = chain
        }
    }

private fun Map<AnimeId, AnimeDetails>.related() = mapValues { it.value.related }

// Each value with what [read] gave for that value's own key; [read] starts again only when the key changes.
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

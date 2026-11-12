package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// A store that lives as long as its process. A title keeps the place it first had, also after it was gone.
class InMemoryLocalList(
    entries: List<ListEntry> = emptyList(),
) : LocalList {
    private val guard = Any()
    private val order = entries.mapTo(LinkedHashSet()) { it.anime.id }
    private val stored = entries.associateTo(mutableMapOf()) { it.anime.id to StoredEntry.settled(it) }
    private val shown = MutableStateFlow(entries)

    override fun observeEntries(): Flow<List<ListEntry>> = shown

    override suspend fun read(id: AnimeId): StoredEntry? = synchronized(guard) { stored[id] }

    override suspend fun readAll(): Map<AnimeId, StoredEntry> =
        synchronized(guard) { order.mapNotNull { id -> stored[id]?.let { id to it } }.toMap() }

    override suspend fun write(entries: Map<AnimeId, StoredEntry?>) {
        synchronized(guard) {
            for ((id, entry) in entries) if (entry == null) stored.remove(id) else stored[id] = entry
            order += entries.keys
            shown.value = order.mapNotNull { stored[it]?.shown }
        }
    }
}

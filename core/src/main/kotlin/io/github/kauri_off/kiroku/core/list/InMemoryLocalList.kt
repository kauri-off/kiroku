package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant

// A store that lives as long as its process. A title keeps the place it first had, also after it left the list, and
// an entry that did not change stays the same object.
class InMemoryLocalList(
    entries: List<ListEntry> = emptyList(),
    owner: SignInId? = null,
) : LocalList {
    private val guard = Any()
    private val titles = LinkedHashMap<AnimeId, Anime>()
    private val stored = mutableMapOf<AnimeId, StoredEntry>()
    private val shown = MutableStateFlow(emptyMap<AnimeId, ListEntry>())
    private var state = StoredList(owner, syncedAt = null)

    init {
        for (entry in entries) {
            titles[entry.anime.id] = entry.anime
            stored[entry.anime.id] = StoredEntry.settled(entry.record)
        }
        shown.value = entries.associateBy { it.anime.id }
    }

    override fun observeEntries(): Flow<List<ListEntry>> = shown.map { it.values.toList() }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = shown.map { it[id] }.distinctUntilChanged { old, new -> old === new }

    override suspend fun state(): StoredList = synchronized(guard) { state }

    override suspend fun read(id: AnimeId): StoredTitle? = synchronized(guard) { titles[id]?.let { StoredTitle(it, stored[id]) } }

    override suspend fun readAll(): Map<AnimeId, StoredEntry> =
        synchronized(guard) { titles.keys.filter { it in stored }.associateWith { stored.getValue(it) } }

    override suspend fun write(entries: Map<AnimeId, StoredEntry?>) {
        synchronized(guard) {
            put(entries)
            show()
        }
    }

    override suspend fun add(
        anime: Anime,
        entry: StoredEntry,
    ) {
        synchronized(guard) {
            titles.getOrPut(anime.id) { anime }
            stored[anime.id] = entry
            show()
        }
    }

    override suspend fun download(
        titles: List<Anime>,
        entries: Map<AnimeId, StoredEntry?>,
        at: Instant,
    ) {
        synchronized(guard) {
            for (anime in titles) if (this.titles[anime.id] != anime) this.titles[anime.id] = anime
            put(entries)
            state = state.copy(syncedAt = at)
            show()
        }
    }

    override suspend fun restore(entries: Map<AnimeId, StoredEntry?>) {
        synchronized(guard) {
            put(entries)
            state = state.copy(syncedAt = null)
            show()
        }
    }

    override suspend fun clear(owner: SignInId?) {
        synchronized(guard) {
            titles.clear()
            stored.clear()
            state = StoredList(owner, syncedAt = null)
            show()
        }
    }

    // An entry of a title the store does not know cannot be shown, and is not kept.
    private fun put(entries: Map<AnimeId, StoredEntry?>) {
        for ((id, entry) in entries) if (entry == null || id !in titles) stored.remove(id) else stored[id] = entry
    }

    private fun show() {
        val before = shown.value
        val now = LinkedHashMap<AnimeId, ListEntry>()
        for ((id, anime) in titles) {
            val record = stored[id]?.shown ?: continue
            val old = before[id]
            now[id] =
                if (old != null && old.anime === anime && old.record == record) old else ListEntry(anime, record.values, record.updatedAt)
        }
        if (now.size != before.size || now.any { (id, entry) -> before[id] !== entry }) shown.value = now
    }
}

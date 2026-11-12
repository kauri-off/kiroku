package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.IOException

// What the demo's store throws while it is told to fail.
class DemoStoreFault(
    message: String,
) : IOException(message)

// The demo's store of the list: the in-memory one, which fails as a database can when told to.
class DemoStore(
    entries: List<ListEntry>,
) : LocalList {
    private val inner = InMemoryLocalList(entries)

    // Every call fails, and an observer's flow ends as soon as this is set.
    val unreadable = MutableStateFlow(false)

    // Writes fail and reads work, as on a full disk.
    val failingWrites = MutableStateFlow(false)

    private val waiting = MutableStateFlow(false)

    // Whether a title waits for MAL to confirm an edit, as the store last took it.
    val unconfirmed: StateFlow<Boolean> = waiting.asStateFlow()

    override fun observeEntries(): Flow<List<ListEntry>> =
        combine(inner.observeEntries(), unreadable) { entries, unreadable ->
            readable(unreadable)
            entries
        }

    override suspend fun read(id: AnimeId): StoredEntry? {
        readable(unreadable.value)
        return inner.read(id)
    }

    override suspend fun readAll(): Map<AnimeId, StoredEntry> {
        readable(unreadable.value)
        return inner.readAll()
    }

    override suspend fun write(entries: Map<AnimeId, StoredEntry?>) {
        readable(unreadable.value)
        if (failingWrites.value) throw DemoStoreFault("The disk is full")
        inner.write(entries)
        waiting.value = inner.readAll().values.any { it.unconfirmed }
    }

    fun reset() {
        unreadable.value = false
        failingWrites.value = false
    }

    private fun readable(unreadable: Boolean) {
        if (unreadable) throw DemoStoreFault("The store cannot be opened")
    }
}

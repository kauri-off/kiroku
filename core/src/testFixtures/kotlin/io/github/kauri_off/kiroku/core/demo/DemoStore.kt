package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.list.InMemoryLocalList
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredList
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.IOException
import java.time.Instant

// What the demo's store throws while it is told to fail.
class DemoStoreFault(
    message: String,
) : IOException(message)

// The demo's store of the list: the in-memory one, which fails as a database can when told to.
class DemoStore(
    entries: List<ListEntry>,
    owner: SignInId,
) : LocalList {
    private val inner = InMemoryLocalList(entries, owner)

    // Every call fails, and an observer's flow ends as soon as this is set.
    val unreadable = MutableStateFlow(false)

    // Writes fail and reads work, as on a full disk.
    val failingWrites = MutableStateFlow(false)

    private val waiting = MutableStateFlow(false)

    // Whether a title waits for MAL to confirm an edit, as the store last took it.
    val unconfirmed: StateFlow<Boolean> = waiting.asStateFlow()

    override fun observeEntries(): Flow<List<ListEntry>> = inner.observeEntries().whileReadable()

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = inner.observeEntry(id).whileReadable()

    override suspend fun state(): StoredList = reading { inner.state() }

    override suspend fun read(id: AnimeId): StoredTitle? = reading { inner.read(id) }

    override suspend fun readAll(): Map<AnimeId, StoredEntry> = reading { inner.readAll() }

    override suspend fun write(entries: Map<AnimeId, StoredEntry?>) = writing { inner.write(entries) }

    override suspend fun add(
        anime: Anime,
        entry: StoredEntry,
    ) = writing { inner.add(anime, entry) }

    override suspend fun download(
        titles: List<Anime>,
        entries: Map<AnimeId, StoredEntry?>,
        at: Instant,
    ) = writing { inner.download(titles, entries, at) }

    override suspend fun restore(entries: Map<AnimeId, StoredEntry?>) = writing { inner.restore(entries) }

    override suspend fun clear(owner: SignInId?) = writing { inner.clear(owner) }

    fun reset() {
        unreadable.value = false
        failingWrites.value = false
    }

    private fun <T> Flow<T>.whileReadable(): Flow<T> =
        combine(this, unreadable) { value, unreadable ->
            readable(unreadable)
            value
        }

    private inline fun <T> reading(call: () -> T): T {
        readable(unreadable.value)
        return call()
    }

    private suspend inline fun writing(call: () -> Unit) {
        readable(unreadable.value)
        if (failingWrites.value) throw DemoStoreFault("The disk is full")
        call()
        waiting.value = inner.readAll().values.any { it.unconfirmed }
    }

    private fun readable(unreadable: Boolean) {
        if (unreadable) throw DemoStoreFault("The store cannot be opened")
    }
}

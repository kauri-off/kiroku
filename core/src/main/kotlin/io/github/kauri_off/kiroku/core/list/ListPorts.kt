package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import kotlinx.coroutines.flow.Flow

// What MAL does for the user's list. A failure is an outcome, never an exception.
interface RemoteList {
    suspend fun fetch(): Outcome<List<ListEntry>>

    // The title's entry as MAL has it now; null when it is not on the list.
    suspend fun fetchEntry(id: AnimeId): Outcome<ListEntry?>

    // Sets the title's values, or removes it for null, and answers with the entry as MAL has it afterwards.
    suspend fun put(
        anime: Anime,
        values: ListValues?,
    ): Outcome<ListEntry?>
}

// A title in the store: what is shown and what MAL last confirmed, apart only while a write of it is unconfirmed.
data class StoredEntry(
    val shown: ListEntry?,
    val confirmed: ListEntry?,
    val unconfirmed: Boolean,
) {
    init {
        require(unconfirmed || (shown != null && shown == confirmed))
    }

    companion object {
        fun settled(entry: ListEntry) = StoredEntry(entry, entry, unconfirmed = false)
    }
}

// What a store does for the user's list. Every call is atomic, and the engine is the only one that writes.
interface LocalList {
    // The shown entries in a stable order, after every write; an equal list may come again.
    fun observeEntries(): Flow<List<ListEntry>>

    suspend fun read(id: AnimeId): StoredEntry?

    suspend fun readAll(): Map<AnimeId, StoredEntry>

    // Stores every entry given and forgets a title given as null, all or none.
    suspend fun write(entries: Map<AnimeId, StoredEntry?>)
}

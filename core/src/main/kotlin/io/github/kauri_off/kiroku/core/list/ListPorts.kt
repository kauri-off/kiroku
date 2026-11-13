package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// What MAL does for the user's list. A failure is an outcome, never an exception.
interface RemoteList {
    // The whole list, or a failure: a title absent from it leaves the list.
    suspend fun fetch(): Outcome<List<ListEntry>>

    // The title's entry as MAL has it now; null when it is not on the list.
    suspend fun fetchEntry(id: AnimeId): Outcome<ListEntry?>

    // Sets the title's values, or removes it for null, and answers with the entry as MAL has it afterwards.
    suspend fun put(
        anime: Anime,
        values: ListValues?,
    ): Outcome<ListEntry?>
}

// An entry without its title: its values and when they last changed.
data class ListRecord(
    val values: ListValues,
    val updatedAt: Instant,
)

val ListEntry.record: ListRecord get() = ListRecord(values, updatedAt)

// A title's entry in the store: what is shown and what MAL last confirmed, apart only while a write is unconfirmed.
data class StoredEntry(
    val shown: ListRecord?,
    val confirmed: ListRecord?,
    val unconfirmed: Boolean,
) {
    init {
        require(unconfirmed || (shown != null && shown == confirmed))
    }

    companion object {
        fun settled(record: ListRecord) = StoredEntry(record, record, unconfirmed = false)
    }
}

// What the store has of one title: its data, and its entry when it has one.
data class StoredTitle(
    val anime: Anime,
    val entry: StoredEntry?,
)

// Whose list the store holds, and when it was last downloaded; no time when it never was or a sync is owed (W5).
data class StoredList(
    val owner: SignInId?,
    val syncedAt: Instant?,
)

// A store of the list. Each call is atomic, only the engine writes, and title data comes in by [download] and [add] alone.
interface LocalList {
    // The shown entries with titles, in stable order, after every change; equal lists may repeat, unchanged entries stay the same objects.
    fun observeEntries(): Flow<List<ListEntry>>

    // One title's shown entry, and nothing for a change to another title.
    fun observeEntry(id: AnimeId): Flow<ListEntry?>

    suspend fun state(): StoredList

    // Null for a title the store does not know; a title it knows may have no entry.
    suspend fun read(id: AnimeId): StoredTitle?

    // Every title that has an entry.
    suspend fun readAll(): Map<AnimeId, StoredEntry>

    // Stores every entry given and forgets one given as null, all or none. No title's data changes.
    suspend fun write(entries: Map<AnimeId, StoredEntry?>)

    // An entry of a title the store may not know: its data is stored only when the store has none of that title.
    suspend fun add(
        anime: Anime,
        entry: StoredEntry,
    )

    // A whole download: every title's data, kept by the store's own rules, the changed entries, the time; a no-op wakes no observer.
    suspend fun download(
        titles: List<Anime>,
        entries: Map<AnimeId, StoredEntry?>,
        at: Instant,
    )

    // W5: [write], and the list counts as never downloaded.
    suspend fun restore(entries: Map<AnimeId, StoredEntry?>)

    // Forgets every entry and the time of the download; the list is [owner]'s from now on.
    suspend fun clear(owner: SignInId?)
}

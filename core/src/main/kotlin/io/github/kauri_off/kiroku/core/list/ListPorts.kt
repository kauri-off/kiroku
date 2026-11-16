package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.changedFrom
import io.github.kauri_off.kiroku.core.model.over
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// What MAL does for the user's list. A failure is an outcome, never an exception.
interface RemoteList {
    // The whole list, or a failure; a list read in pages can miss a title that is still on it.
    suspend fun fetch(): Outcome<List<ListEntry>>

    // The title's entry as MAL has it now; null when it is not on the list.
    suspend fun fetchEntry(id: AnimeId): Outcome<ListEntry?>

    // Sets the title's values, or removes it for null, and answers with the entry as MAL has it afterwards.
    suspend fun put(
        anime: Anime,
        values: SentValues?,
    ): Outcome<ListEntry?>
}

// A write's [fields] of [values] alone; MAL keeps a value left out, and never gets a status the app does not know.
class SentValues(
    val values: ListValues,
    fields: Set<ListField>,
) {
    val fields: Set<ListField> = ListField.entries.filterTo(LinkedHashSet()) { it in fields }

    init {
        require(ListField.Status !in fields || values.status is ListStatus)
    }

    val status: ListStatus? get() = if (ListField.Status in fields) values.status as ListStatus else null

    val watchedEpisodes: Int? get() = if (ListField.WatchedEpisodes in fields) values.watchedEpisodes else null

    // The entry MAL holds after this write over [known], or over no entry of the title.
    fun over(known: ListValues?): ListValues =
        values.over(known ?: ListValues(checkNotNull(status) { NO_STATUS }, 0, null, null, null), fields)

    private val sent: Map<ListField, Any?> get() = fields.associateWith { it.of(values) }

    override fun equals(other: Any?): Boolean = other is SentValues && sent == other.sent

    override fun hashCode(): Int = sent.hashCode()

    override fun toString(): String = "SentValues($sent)"

    private companion object {
        const val NO_STATUS = "A new entry needs a status"
    }
}

// Every value, as a write that sets them all.
val ListValues.sent: SentValues
    get() = SentValues(this, ListField.entries.filterTo(mutableSetOf()) { it != ListField.Status || status is ListStatus })

// Section 7.3: what the edit changed from [from] and [confirmed] lacks, or all [named] when MAL's values are unknown.
fun ListValues.sentOver(
    from: ListValues?,
    confirmed: ListValues?,
    named: Set<ListField>? = null,
): SentValues? {
    val unknown = if (status is ListStatus) emptySet() else setOf(ListField.Status)
    if (confirmed == null) return SentValues(this, changedFrom(ListValues(status, 0, null, null, null)) + ListField.Status - unknown)
    val changed = named ?: changedFrom(confirmed).intersect(from?.let { changedFrom(it) } ?: ListField.entries.toSet())
    val fields = changed - unknown
    return if (fields.isEmpty()) null else SentValues(this, fields)
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

    // The shown entries of [ids] that exist, read together.
    fun observeEntriesOf(ids: Set<AnimeId>): Flow<Map<AnimeId, ListEntry>>

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

    // A whole download: every title's data, kept by the store's own rules, the changed entries, the time, or none while a
    // download is still owed; a no-op wakes no observer.
    suspend fun download(
        titles: List<Anime>,
        entries: Map<AnimeId, StoredEntry?>,
        at: Instant?,
    )

    // W5: [write], and the list counts as never downloaded.
    suspend fun restore(entries: Map<AnimeId, StoredEntry?>)

    // Forgets every entry and the time of the download; the list is [owner]'s from now on.
    suspend fun clear(owner: SignInId?)
}

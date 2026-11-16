package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.tracked
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

// Where the times of a title's episodes come from, asked by MAL id. A failure is an outcome, never an exception.
interface AiringSource {
    // Each of [ids] whole or as a failure, null for a title it does not know, in as many requests as that takes.
    suspend fun fetch(ids: Set<AnimeId>): Map<AnimeId, Outcome<FetchedAiring?>>
}

// What the source has of one title: the episodes it has a time for, which may be none, with the rest of its schedule.
data class FetchedAiring(
    val schedule: AiringSchedule,
    // Nothing the source has at or after this instant is left out; earlier episodes may be. Null: nothing is left out.
    val completeFrom: Instant? = null,
)

// A title's schedule after its last answer, none when the source does not know it: each episode number once, in order.
data class StoredAiring(
    val schedule: AiringSchedule?,
    // The device's time when that answer was asked for.
    val fetchedAt: Instant,
)

// A store of airing data. Each call is atomic, and only the engine writes.
interface AiringStore {
    // Every title stored, after every change; equal maps may repeat.
    fun observe(): Flow<Map<AnimeId, StoredAiring>>

    suspend fun read(): Map<AnimeId, StoredAiring>

    // Stores every title given and forgets one given as null, all or none.
    suspend fun write(titles: Map<AnimeId, StoredAiring?>)
}

// Section 4: the titles airing data is kept for, the unfinished ones of the list in Watching, Planned or On hold.
val ListEntry.tracked: Boolean get() = status.tracked && anime.airingStatus.effective != AiringStatus.Finished

// A13: how long after its last episode a finished title keeps its data, N2's three days after the longest delay.
val finaleKeptFor: Duration = 3.days + 3.hours

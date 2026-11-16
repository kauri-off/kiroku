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
    // Each of [ids] with what the source has of it, in as many requests as that takes: null for a title it does not know,
    // a failure for one it could not answer whole. A title left out counts as failed, and one not asked is ignored.
    suspend fun fetch(ids: Set<AnimeId>): Map<AnimeId, Outcome<FetchedAiring?>>
}

// What the source has of one title: the episodes it has a time for, which may be none, with the rest of its schedule.
data class FetchedAiring(
    val schedule: AiringSchedule,
    // The source has no episode at or after this instant but the ones given, and may have earlier ones it left out;
    // null when it left out none.
    val completeFrom: Instant? = null,
)

// What is stored of one title: its schedule after the last answer, none for a title the source does not know, and when
// that answer was asked for. An episode number occurs once, and the episodes are in the order of their numbers.
data class StoredAiring(
    val schedule: AiringSchedule?,
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

// A13: how long after its last episode a title MAL has marked finished keeps its data: N2's three days after the
// longest delay of section 5.3.
val finaleKeptFor: Duration = 3.days + 3.hours

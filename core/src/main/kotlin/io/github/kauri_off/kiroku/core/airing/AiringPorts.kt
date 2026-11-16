package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// Where episode times come from. What it could not find out is a failure, never an exception and never "not known".
interface AiringSource {
    // Each of [titles] under its MAL id, whole or a failure: no episode at or after [since] is left out, nor the latest aired.
    suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>>
}

// A title the source knows, with no episode time too; null stands only for a title it has no record of.
data class FetchedAiring(
    val schedule: AiringSchedule,
    // Only episodes before this instant are left out, and never the latest that has aired. Null: nothing is left out.
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

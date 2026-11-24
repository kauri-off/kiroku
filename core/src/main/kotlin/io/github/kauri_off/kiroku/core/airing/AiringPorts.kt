package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// Where episode times come from. What it could not find out is a failure, never an exception and never "not known".
interface AiringSource {
    // Each of [titles] under its MAL id, whole or a failure: no episode at or after [since] is left out, nor the latest aired.
    suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>>

    // The same, handing [whole] each title once its answer is final, before the call returns: a call cut short keeps those.
    suspend fun fetch(
        titles: Collection<Anime>,
        since: Instant,
        // Of [titles], those a person pulled: a wait the source sets itself after a failure does not hold them back, a refusal does.
        pulled: Set<AnimeId>,
        whole: suspend (Map<AnimeId, Outcome<FetchedAiring?>>) -> Unit,
    ): Map<AnimeId, Outcome<FetchedAiring?>> = fetch(titles, since)

    // N3: the same at once, however long a schedule: a title may be answered from a later instant on, without the latest aired.
    suspend fun fetchComing(
        titles: Collection<Anime>,
        since: Instant,
    ): Map<AnimeId, Outcome<FetchedAiring?>> = fetch(titles, since)

    suspend fun isCurrent(owner: SignInId?): Boolean = true

    suspend fun fetchAnswers(
        owner: SignInId?,
        titles: Collection<Anime>,
        since: Instant,
        pulled: Set<AnimeId>,
        coming: Boolean,
        whole: suspend (Map<AnimeId, Outcome<FetchedAiring?>>) -> Unit,
    ): Map<AnimeId, Outcome<FetchedAiring?>> = if (coming) fetchComing(titles, since) else fetch(titles, since, pulled, whole)
}

// A title the source knows, with no episode time too; null stands only for a title it has no record of.
data class FetchedAiring(
    val schedule: AiringSchedule,
    // Null: nothing is left out. Else only episodes before it are; but for fetchComing, never the latest aired, and no later than asked.
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
    // Every title stored: as it is now for a new observer, then after every change; equal maps may repeat.
    fun observe(): Flow<Map<AnimeId, StoredAiring>>

    suspend fun read(): Map<AnimeId, StoredAiring>

    // Stores every title given and forgets one given as null, all or none.
    suspend fun write(titles: Map<AnimeId, StoredAiring?>)

    suspend fun writeAnswers(
        titles: Map<AnimeId, StoredAiring?>,
        owner: SignInId?,
    ): Boolean {
        write(titles)
        return true
    }
}

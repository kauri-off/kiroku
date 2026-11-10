package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import kotlin.time.Duration

data class ListRequest(
    val id: AnimeId,
    val values: ListValues?,
)

class DemoServer(
    entries: List<ListEntry>,
    private val latency: Duration,
) {
    val offline = MutableStateFlow(false)

    val failingLoads = MutableStateFlow(false)

    val failingWrites = MutableStateFlow(false)

    val sessionExpired = MutableStateFlow(false)

    private val guard = Any()
    private val list = entries.associateBy { it.anime.id }.toMutableMap()
    private val received = mutableListOf<ListRequest>()

    val requests: List<ListRequest> get() = synchronized(guard) { received.toList() }

    fun entry(id: AnimeId): ListEntry? = synchronized(guard) { list[id] }

    fun entries(): List<ListEntry> = synchronized(guard) { list.values.toList() }

    suspend fun load(needsSignIn: Boolean): DataError? {
        delay(latency)
        return when {
            offline.value -> DataError.NoConnection
            needsSignIn && sessionExpired.value -> DataError.SessionExpired
            failingLoads.value -> DataError.ServerError
            else -> null
        }
    }

    suspend fun put(
        anime: Anime,
        values: ListValues?,
        at: Instant,
    ): Outcome<Unit> {
        delay(latency)
        if (offline.value) return Outcome.Failure(DataError.NoConnection)
        synchronized(guard) { received += ListRequest(anime.id, values) }
        val error =
            when {
                sessionExpired.value -> DataError.SessionExpired
                failingWrites.value -> DataError.ServerError
                else -> null
            }
        if (error != null) return Outcome.Failure(error)
        synchronized(guard) {
            if (values == null) list.remove(anime.id) else list[anime.id] = ListEntry(anime, values, at)
        }
        return Outcome.Success(Unit)
    }
}

internal fun DataError?.toOutcome(): Outcome<Unit> = if (this == null) Outcome.Success(Unit) else Outcome.Failure(this)

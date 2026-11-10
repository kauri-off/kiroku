package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Clock
import kotlin.time.Duration

data class ListRequest(
    val id: AnimeId,
    val values: ListValues?,
)

class DemoServer(
    entries: List<ListEntry>,
    private val clock: Clock,
    private val latency: Duration,
) {
    val offline = MutableStateFlow(false)

    val failingLoads = MutableStateFlow(false)

    val failingWrites = MutableStateFlow(false)

    val refusing = MutableStateFlow(false)

    val sessionExpired = MutableStateFlow(false)

    @Volatile
    var writeLatency: (ListRequest) -> Duration = { latency }

    private val guard = Any()
    private val list = entries.associateBy { it.anime.id }.toMutableMap()
    private val received = mutableListOf<ListRequest>()
    private var downloads = 0

    val requests: List<ListRequest> get() = synchronized(guard) { received.toList() }

    val listDownloads: Int get() = synchronized(guard) { downloads }

    fun entry(id: AnimeId): ListEntry? = synchronized(guard) { list[id] }

    fun entries(): List<ListEntry> = synchronized(guard) { list.values.toList() }

    fun editOnWebsite(
        anime: Anime,
        values: ListValues?,
    ) {
        synchronized(guard) { store(anime, values) }
    }

    suspend fun load(needsSignIn: Boolean): DataError? {
        delay(latency)
        return when {
            offline.value -> DataError.NoConnection
            needsSignIn && sessionExpired.value -> DataError.SessionExpired
            refusing.value -> DataError.Refused
            failingLoads.value -> DataError.ServerError
            else -> null
        }
    }

    suspend fun download(): Outcome<List<ListEntry>> {
        load(needsSignIn = true)?.let { return Outcome.Failure(it) }
        val snapshot =
            synchronized(guard) {
                downloads++
                list.values.toList()
            }
        delay(latency)
        return Outcome.Success(snapshot)
    }

    suspend fun put(
        anime: Anime,
        values: ListValues?,
    ): Outcome<Unit> {
        val request = ListRequest(anime.id, values)
        delay(writeLatency(request))
        if (offline.value) return Outcome.Failure(DataError.NoConnection)
        synchronized(guard) { received += request }
        val error =
            when {
                sessionExpired.value -> DataError.SessionExpired
                refusing.value -> DataError.Refused
                failingWrites.value -> DataError.ServerError
                else -> null
            }
        if (error != null) return Outcome.Failure(error)
        synchronized(guard) { store(anime, values) }
        return Outcome.Success(Unit)
    }

    private fun store(
        anime: Anime,
        values: ListValues?,
    ) {
        if (values == null) list.remove(anime.id) else list[anime.id] = ListEntry(anime, values, clock.instant())
    }
}

internal fun DataError?.toOutcome(): Outcome<Unit> = if (this == null) Outcome.Success(Unit) else Outcome.Failure(this)

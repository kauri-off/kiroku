package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.RemoteList
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Clock
import kotlin.time.Duration

data class ListRequest(
    val id: AnimeId,
    val values: ListValues?,
)

// The demo's MAL: the list as the website has it, with scripted latency and failures.
class DemoServer(
    entries: List<ListEntry>,
    private val clock: Clock,
    latency: Duration,
) : RemoteList {
    // How long every answer takes.
    @Volatile
    var latency: Duration = latency

    val offline = MutableStateFlow(false)

    val failingLoads = MutableStateFlow(false)

    val failingWrites = MutableStateFlow(false)

    val refusing = MutableStateFlow(false)

    // Refuses writes alone; reads still work.
    val refusingWrites = MutableStateFlow(false)

    val sessionExpired = MutableStateFlow(false)

    @Volatile
    var writeLatency: (ListRequest) -> Duration = { this.latency }

    private val sending = MutableStateFlow(0)

    // The writes received and not answered yet.
    val writing: StateFlow<Int> = sending.asStateFlow()

    private val guard = Any()
    private val list = entries.associateBy { it.anime.id }.toMutableMap()

    // A title's data is MAL's own: what a write says of it counts only for a title MAL has not seen.
    private val titles = entries.associateTo(mutableMapOf()) { it.anime.id to it.anime }
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

    // MAL's data of a title changes, as when its score moves; an entry of the title keeps its values and its time.
    fun retitle(anime: Anime) {
        synchronized(guard) {
            titles[anime.id] = anime
            list[anime.id]?.let { list[anime.id] = it.copy(anime = anime) }
        }
    }

    // Every failure off and the list back to [entries], as a new server would have it.
    fun reset(
        entries: List<ListEntry>,
        latency: Duration,
    ) {
        listOf(offline, failingLoads, failingWrites, refusing, refusingWrites, sessionExpired).forEach { it.value = false }
        this.latency = latency
        writeLatency = { this.latency }
        synchronized(guard) {
            list.clear()
            entries.associateByTo(list) { it.anime.id }
            titles.clear()
            entries.associateTo(titles) { it.anime.id to it.anime }
        }
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

    override suspend fun fetch(): Outcome<List<ListEntry>> {
        load(needsSignIn = true)?.let { return Outcome.Failure(it) }
        val snapshot =
            synchronized(guard) {
                downloads++
                list.values.toList()
            }
        delay(latency)
        return Outcome.Success(snapshot)
    }

    override suspend fun fetchEntry(id: AnimeId): Outcome<ListEntry?> =
        load(needsSignIn = true)?.let { Outcome.Failure(it) } ?: Outcome.Success(entry(id))

    override suspend fun put(
        anime: Anime,
        values: ListValues?,
    ): Outcome<ListEntry?> {
        sending.update { it + 1 }
        try {
            return answer(anime, values)
        } finally {
            sending.update { it - 1 }
        }
    }

    private suspend fun answer(
        anime: Anime,
        values: ListValues?,
    ): Outcome<ListEntry?> {
        val request = ListRequest(anime.id, values)
        delay(writeLatency(request))
        if (offline.value) return Outcome.Failure(DataError.NoConnection)
        synchronized(guard) { received += request }
        val error =
            when {
                sessionExpired.value -> DataError.SessionExpired
                refusing.value || refusingWrites.value -> DataError.Refused
                failingWrites.value -> DataError.ServerError
                else -> null
            }
        if (error != null) return Outcome.Failure(error)
        return Outcome.Success(
            synchronized(guard) {
                store(anime, values)
                list[anime.id]
            },
        )
    }

    private fun store(
        anime: Anime,
        values: ListValues?,
    ) {
        val known = titles.getOrPut(anime.id) { anime }
        if (values == null) list.remove(anime.id) else list[anime.id] = ListEntry(known, values, clock.instant())
    }
}

internal fun DataError?.toOutcome(): Outcome<Unit> = if (this == null) Outcome.Success(Unit) else Outcome.Failure(this)

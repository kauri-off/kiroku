package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredList
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

// The user's list in the database, read on [dispatcher] and shared while [scope] lives; [unexpected] hears of a row left out as unreadable.
class RoomLocalList(
    private val database: KirokuDatabase,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val unexpected: (Throwable) -> Unit,
) : LocalList {
    private class Listed(
        val row: ListedRow,
        val entry: ListEntry?,
    )

    private class Read(
        val number: Long,
        val entries: List<ListEntry>,
    )

    private val listDao = database.list()
    private val titleDao = database.titles()

    // One read of the list at a time, so a later read is of a later list; it guards the three fields below.
    private val reading = Mutex()
    private var reads = 0L

    // What the last read made of each row: an entry whose row did not change is not made again.
    private var listed = emptyMap<Long, Listed>()
    private var last = emptyList<ListEntry>()

    // One read per change for all collectors, while there is one; a failure reaches each, then the watch starts again.
    private val changes =
        flow<Result<Read>> {
            while (true) {
                try {
                    // Its first emission covers a change made while a collector was joining.
                    val changed = database.invalidationTracker.createFlow(TitleRow.TABLE, ListEntryRow.TABLE)
                    changed.conflate().collect { emit(Result.success(read())) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(Result.failure(e))
                    delay(RETRY)
                }
            }
        }.conflate().shareIn(scope + dispatcher, SharingStarted.WhileSubscribed(), replay = 0)

    // A collector starts with a read of its own, so never with a list older than the store; then it shares the reads.
    override fun observeEntries(): Flow<List<ListEntry>> =
        flow {
            var newest = 0L
            changes.onSubscription { emit(Result.success(read())) }.collect {
                val read = it.getOrThrow()
                if (read.number > newest) {
                    newest = read.number
                    emit(read.entries)
                }
            }
        }.distinctUntilChanged { old, new -> old === new }
            .flowOn(dispatcher)
            .conflate()

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> =
        listDao
            .observeListed(id.value)
            .distinctUntilChanged()
            .map { it?.toEntry() }
            .flowOn(dispatcher)

    override suspend fun state(): StoredList = withContext(dispatcher) { listDao.state().toStored() }

    override suspend fun read(id: AnimeId): StoredTitle? = withContext(dispatcher) { listDao.title(id.value)?.toStored() }

    override suspend fun readAll(): Map<AnimeId, StoredEntry> =
        withContext(dispatcher) { listDao.entries().mapNotNull { readable(unexpected) { AnimeId(it.titleId) to it.toStored() } }.toMap() }

    override suspend fun write(entries: Map<AnimeId, StoredEntry?>) = transaction { put(entries) }

    override suspend fun add(
        anime: Anime,
        entry: StoredEntry,
    ) = transaction {
        titleDao.insertIfAbsent(listOf(anime.toRow()))
        put(mapOf(anime.id to entry))
    }

    override suspend fun download(
        titles: List<Anime>,
        entries: Map<AnimeId, StoredEntry?>,
        at: Instant,
    ) = transaction {
        titleDao.merge(titles.map { it.toRow() })
        put(entries)
        listDao.setState { it.copy(syncedAt = at.toColumn()) }
    }

    override suspend fun restore(entries: Map<AnimeId, StoredEntry?>) =
        transaction {
            put(entries)
            listDao.setState { it.copy(syncedAt = null) }
        }

    // With every title, unless the list was nobody's, empty, and stays nobody's: then no title is of a sign-in.
    override suspend fun clear(owner: SignInId?) {
        transaction {
            val nobodys = owner == null && listDao.state().owner == null && !listDao.hasEntries()
            listDao.deleteAll()
            if (!nobodys) titleDao.deleteAll()
            listDao.setState { it.copy(owner = owner?.value, syncedAt = null) }
        }
        reading.withLock { entries(emptyList()) }
    }

    private suspend fun put(entries: Map<AnimeId, StoredEntry?>) =
        listDao.put(entries.entries.associate { (id, entry) -> id.value to entry?.toRow(id.value) })

    private suspend fun transaction(block: suspend () -> Unit) = withContext(dispatcher) { database.transaction(block) }

    private suspend fun read(): Read = reading.withLock { Read(++reads, entries(listDao.listed())) }

    // The list itself is the last one when no entry changed; a row is read, and reported unreadable, once.
    private fun entries(rows: List<ListedRow>): List<ListEntry> {
        val now = HashMap<Long, Listed>(rows.size * 2)
        val entries =
            rows.mapNotNull { row ->
                val kept = listed[row.title.id]?.takeIf { it.row == row } ?: Listed(row, readable(unexpected) { row.toEntry() })
                now[row.title.id] = kept
                kept.entry
            }
        listed = now
        if (entries.size != last.size || entries.indices.any { entries[it] !== last[it] }) last = entries
        return last
    }

    private companion object {
        val RETRY = 1.seconds
    }
}

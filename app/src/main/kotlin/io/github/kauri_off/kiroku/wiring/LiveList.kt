package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.list.RemoteList
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.Instant

// The list of a live source: the engine over MAL's list and the source's store.
internal fun liveList(
    parts: LiveParts,
    session: SessionRepository,
    store: (LiveParts) -> LocalList,
): ListRepository =
    ListEngine(UnreachableList, StoreOnFirstUse { store(parts) }, session.observeSession(), parts.clock, parts.scope, parts.unexpected)

// MAL's list, which no client reaches yet.
private object UnreachableList : RemoteList {
    private val unavailable = Outcome.Failure(DataError.ServerError)

    override suspend fun fetch(): Outcome<List<ListEntry>> = unavailable

    override suspend fun fetchEntry(id: AnimeId): Outcome<ListEntry?> = unavailable

    override suspend fun put(
        anime: Anime,
        values: ListValues?,
    ): Outcome<ListEntry?> = unavailable
}

// The store is built at the engine's first call of it, which comes after the session is known, and off the main thread.
private class StoreOnFirstUse(
    build: () -> LocalList,
) : LocalList {
    private val built = lazy(build)

    private suspend fun store(): LocalList = if (built.isInitialized()) built.value else withContext(Dispatchers.IO) { built.value }

    override fun observeEntries(): Flow<List<ListEntry>> = flow { emitAll(store().observeEntries()) }

    override fun observeEntry(id: AnimeId): Flow<ListEntry?> = flow { emitAll(store().observeEntry(id)) }

    override suspend fun state() = store().state()

    override suspend fun read(id: AnimeId) = store().read(id)

    override suspend fun readAll() = store().readAll()

    override suspend fun write(entries: Map<AnimeId, StoredEntry?>) = store().write(entries)

    override suspend fun add(
        anime: Anime,
        entry: StoredEntry,
    ) = store().add(anime, entry)

    override suspend fun download(
        titles: List<Anime>,
        entries: Map<AnimeId, StoredEntry?>,
        at: Instant,
    ) = store().download(titles, entries, at)

    override suspend fun restore(entries: Map<AnimeId, StoredEntry?>) = store().restore(entries)

    override suspend fun clear(owner: SignInId?) = store().clear(owner)
}

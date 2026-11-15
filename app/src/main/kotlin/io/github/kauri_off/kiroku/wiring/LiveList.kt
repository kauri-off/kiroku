package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.list.ListEngine
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.data.list.MalRemoteList
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.Instant

// The list of a live source: the engine over MAL's list and the source's store, whose owner's sorts go with it (S9).
internal fun liveList(
    parts: LiveParts,
    session: MalSession,
    store: (LiveParts) -> LocalList,
    forgetSorts: suspend () -> Unit,
): ListRepository =
    ListEngine(
        MalRemoteList(parts.http, session),
        StoreOnFirstUse({ store(parts) }, forgetSorts),
        session.observeSession(),
        parts.clock,
        parts.scope,
        parts.unexpected,
    )

// The store is built at the engine's first call of it, which comes after the session is known, and off the main thread.
private class StoreOnFirstUse(
    build: () -> LocalList,
    private val forgetSorts: suspend () -> Unit,
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
        at: Instant?,
    ) = store().download(titles, entries, at)

    override suspend fun restore(entries: Map<AnimeId, StoredEntry?>) = store().restore(entries)

    // Only a new owner forgets the sorts, and first: a list still the last owner's after a failure is cleared again.
    override suspend fun clear(owner: SignInId?) {
        val store = store()
        if (store.state().owner != owner) forgetSorts()
        store.clear(owner)
    }
}

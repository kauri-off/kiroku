package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaPublication
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.manga.RoomMangaList
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock

class SandboxManga(
    database: () -> KirokuDatabase,
    private val session: MalSession,
    private val clock: Clock,
    private val device: Device,
    private val waitMillis: () -> Long,
    poster: (Long) -> String,
    unexpected: (Throwable) -> Unit,
    scope: CoroutineScope,
) : MangaListReader,
    MangaRepository {
    private val entries = Entries.map { it.copy(posterUrl = poster(it.id.value)) }
    private val list by lazy {
        RoomMangaList(database(), session, { answer(entries) }, clock, unexpected).also { repository ->
            scope.launch { session.observeSession().collect { repository.forgetRetired(it.owner) } }
        }
    }

    override suspend fun stored(owner: SignInId) = withContext(Dispatchers.IO) { list.stored(owner) }

    override suspend fun refresh(owner: SignInId) = withContext(Dispatchers.IO) { list.refresh(owner) }

    override fun observeOwner() = session.observeOwner()

    override suspend fun stored(
        id: MangaId,
        owner: SessionOwner,
    ): Outcome<StoredManga?> =
        session.withOwner(owner) { Outcome.Success(details[id]?.let { StoredManga(it, clock.instant()) }) }
            ?: Outcome.Failure(DataError.ListEnded)

    override suspend fun refresh(
        id: MangaId,
        owner: SessionOwner,
        canDispatch: () -> Boolean,
    ): Outcome<StoredManga>? {
        if (!canDispatch()) return null
        val title = details[id] ?: return Outcome.Failure(DataError.NotFound)
        val outcome = answer(StoredManga(title, clock.instant()))
        return session.withOwner(owner) { outcome } ?: Outcome.Failure(DataError.ListEnded)
    }

    private suspend fun <T> answer(value: T): Outcome<T> {
        delay(waitMillis())
        return if (device.isOnline()) Outcome.Success(value) else Outcome.Failure(DataError.NoConnection)
    }

    private val details =
        entries.associate { entry ->
            entry.id to
                MangaDetails(
                    entry.id,
                    entry.titles,
                    entry.posterUrl,
                    null,
                    null,
                    MangaFormat.Known.Manga,
                    MangaPublication.Known.Finished,
                    null,
                    null,
                    null,
                )
        }

    private companion object {
        val Entries =
            listOf(
                MangaListEntry(MangaId(1), Titles("Paper Observatory"), MangaReadingStatus("reading"), chaptersRead = 12, volumesRead = 2),
                MangaListEntry(MangaId(2), Titles("The Lantern District"), MangaReadingStatus("completed"), chaptersRead = 54, score = 9),
                MangaListEntry(MangaId(3), Titles("Rain at the Edge of Town"), MangaReadingStatus("plan_to_read"), chaptersRead = 0),
                MangaListEntry(MangaId(4), Titles("Between the Tides"), MangaReadingStatus("on_hold"), chaptersRead = 7),
            )
    }
}

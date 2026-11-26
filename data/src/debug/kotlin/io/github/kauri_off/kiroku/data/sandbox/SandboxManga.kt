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
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditor
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.manga.MangaPatchResult
import io.github.kauri_off.kiroku.data.manga.RoomMangaList
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.session.Attempt
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.delay
import java.time.Clock

class SandboxManga(
    database: () -> KirokuDatabase,
    private val session: MalSession,
    private val clock: Clock,
    private val device: Device,
    private val mal: SandboxMal,
    poster: (Long) -> String,
    unexpected: (Throwable) -> Unit,
    private val retired: (SignInId) -> Boolean,
) : MangaListEditor,
    MangaRepository {
    private val entries = Entries.map { it.copy(posterUrl = poster(it.id.value), explicitlyUnrated = it.score == null) }
    private var listOwner: SignInId? = null
    private var currentEntries = entries

    private val list by lazy {
        RoomMangaList(database, session, { owner, _ -> readList(owner) }, clock, unexpected, retired, writer = ::patch)
    }

    override suspend fun stored(owner: SignInId) = list.stored(owner)

    override fun observe(owner: SignInId) = list.observe(owner)

    override suspend fun refresh(
        owner: SignInId,
        canDispatch: () -> Boolean,
    ) = list.refresh(owner, canDispatch)

    override suspend fun edit(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
        retryFrom: MangaListEntry?,
    ) = list.edit(owner, id, changes, retryFrom)

    private suspend fun readList(owner: SignInId): Outcome<List<MangaListEntry>> = withList(owner) { Outcome.Success(currentEntries) }

    private suspend fun patch(
        owner: SignInId,
        id: MangaId,
        changes: MangaListEdit,
    ): MangaPatchResult =
        when (
            val result =
                withList(owner) {
                    when (mal.writes) {
                        SandboxWrites.Refused -> Outcome.Failure(DataError.Refused)
                        SandboxWrites.Fail -> Outcome.Success(MangaPatchResult.Unknown(DataError.ServerError))
                        SandboxWrites.Work -> {
                            if (currentEntries.none { it.id == id }) return@withList Outcome.Failure(DataError.NotFound)
                            currentEntries =
                                currentEntries.map { if (it.id == id) changes.appliedTo(it).copy(updatedAt = clock.instant()) else it }
                            Outcome.Success(MangaPatchResult.Accepted)
                        }
                    }
                }
        ) {
            is Outcome.Failure -> MangaPatchResult.Refused(result.error)
            is Outcome.Success -> result.value
        }

    private suspend fun <T> withList(
        owner: SignInId,
        run: () -> Outcome<T>,
    ): Outcome<T> =
        session.authorised(owner) { bearer ->
            delay(mal.delayMillis)
            when {
                mal.offline || !device.isOnline() -> Attempt.Answered(Outcome.Failure(DataError.NoConnection))
                !mal.acceptsAccess(bearer) -> Attempt.Rejected
                else -> {
                    var result: Outcome<T> = Outcome.Failure(DataError.ListEnded)
                    session.whileOwner(owner) {
                        if (session.mayWriteAs(owner) && !retired(owner)) {
                            if (listOwner != owner) {
                                listOwner = owner
                                currentEntries = entries
                            }
                            result = run()
                        }
                        true
                    }
                    Attempt.Answered(result)
                }
            }
        }

    private var cardOwner: SessionOwner? = null
    private val cards = mutableMapOf<MangaId, StoredManga>()

    override fun observeOwner() = session.observeOwner()

    override suspend fun stored(
        id: MangaId,
        owner: SessionOwner,
    ): Outcome<StoredManga?> =
        session.withOwner(owner) { Outcome.Success(cards[id].takeIf { cardOwner === owner }) }
            ?: Outcome.Failure(DataError.ListEnded)

    override suspend fun refresh(
        id: MangaId,
        owner: SessionOwner,
        canDispatch: () -> Boolean,
    ): Outcome<StoredManga>? {
        if (!canDispatch()) return null
        val title = details[id] ?: return Outcome.Failure(DataError.NotFound)
        delay(mal.delayMillis)
        return session.withOwner(owner) {
            if (!device.isOnline()) return@withOwner Outcome.Failure(DataError.NoConnection)
            if (cardOwner !== owner) {
                cards.clear()
                cardOwner = owner
            }
            val stored = StoredManga(title, clock.instant())
            cards[id] = stored
            Outcome.Success(stored)
        } ?: Outcome.Failure(DataError.ListEnded)
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

package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.network.AnotherTitleAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant

// The titles of section 7.1: what the store holds, refreshed from MAL's page; a refresh that fails leaves the store as it was.
class MalAnimeRepository(
    private val titles: MalTitles,
    private val store: TitleStore,
    private val clock: Clock,
    // The setting "Show adult content in search", which inside a title changes only its recommendations.
    private val adult: suspend () -> Boolean,
    private val unexpected: (Throwable) -> Unit,
) : AnimeRepository {
    override fun observeAnime(id: AnimeId): Flow<Anime?> = store.observeAnime(id).readable()

    override fun observeDetails(id: AnimeId): Flow<AnimeDetails?> =
        store
            .observeDetails(id)
            .map { it?.details }
            .distinctUntilChanged()
            .readable()

    override fun observeDetailsOf(ids: Set<AnimeId>): Flow<Map<AnimeId, AnimeDetails>> =
        store.observeDetailsOf(ids).distinctUntilChanged().readable()

    override fun observeRefreshedAt(id: AnimeId): Flow<Instant?> =
        store
            .observeDetails(id)
            .map { it?.fetchedAt }
            .distinctUntilChanged()
            .readable()

    override fun observePagesRead(): Flow<Set<AnimeId>> = store.observeDetailed().readable()

    // An answer that comes after a sign-out cleared the store is not stored: its list has ended.
    override suspend fun refresh(id: AnimeId): Outcome<Unit> {
        val begun =
            when (val held = stored { store.holder() }) {
                is Outcome.Failure -> return held
                is Outcome.Success -> held.value
            }
        val answer = titles.title(id, adult())
        if (answer !is Outcome.Success) return answer as Outcome.Failure
        val (anime, details, unread) = answer.value
        // A page of another title is not the one asked for, which would otherwise never get its details.
        if (anime.id != id) {
            unexpected(AnotherTitleAnswer())
            return Outcome.Failure(DataError.ServerError)
        }
        return when (val stored = stored { store.refresh(anime, details, clock.instant(), unread, begun) }) {
            is Outcome.Failure -> stored
            is Outcome.Success -> if (stored.value) Outcome.Success(Unit) else Outcome.Failure(DataError.ListEnded)
        }
    }

    private suspend fun <T> stored(call: suspend () -> T): Outcome<T> =
        try {
            Outcome.Success(call())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(e)
            Outcome.Failure(DataError.StorageError)
        }

    // A store that cannot be read is reported, and ends the flow as section 6.1 says.
    private fun <T> Flow<T>.readable(): Flow<T> =
        catch { e ->
            if (e !is Exception || e is CancellationException) throw e
            unexpected(e)
            throw DataException(DataError.StorageError, e)
        }
}

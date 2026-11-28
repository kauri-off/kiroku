package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AdultSearchChoice
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.data.db.Holder
import io.github.kauri_off.kiroku.data.db.TitleStore
import io.github.kauri_off.kiroku.data.db.Unread
import io.github.kauri_off.kiroku.data.network.AnotherTitleAnswer
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

// The titles of section 7.1: what the store holds, refreshed from MAL's page; a refresh that fails leaves the store as it was.
class MalAnimeRepository(
    private val titles: MalTitles,
    private val store: TitleStore,
    private val session: MalSession,
    private val clock: Clock,
    // The setting "Show adult content in search", which inside a title changes only its recommendations.
    private val preferences: StoredPreferences,
    private val unexpected: (Throwable) -> Unit,
) : AnimeRepository {
    private var sequence = 0L
    private val publishedDetails = mutableMapOf<AnimeId, Long>()
    private val publishedRecommendations = mutableMapOf<AnimeId, Long>()

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
        val owner = session.currentOwner() ?: return Outcome.Failure(DataError.StorageError)
        val begun =
            when (val held = stored { store.holder() }) {
                is Outcome.Failure -> return held
                is Outcome.Success -> held.value
            }
        val (choice, order) = preferences.withAdultChoice { it to ++sequence }
        val answer = titles.title(id, choice?.enabled == true) { session.isCurrentOwner(owner) }
        return session.withOwner(owner) {
            when (answer) {
                is Outcome.Failure -> answer
                is Outcome.Success -> publish(id, answer.value, begun, choice, order)
            }
        } ?: Outcome.Failure(DataError.ListEnded)
    }

    private suspend fun publish(
        id: AnimeId,
        title: MalTitle,
        begun: Holder,
        choice: AdultSearchChoice?,
        order: Long,
    ): Outcome<Unit> {
        val (anime, details, unread) = title
        // A page of another title is not the one asked for, which would otherwise never get its details.
        if (anime.id != id) {
            unexpected(AnotherTitleAnswer())
            return Outcome.Failure(DataError.ServerError)
        }
        val stored =
            stored {
                preferences.withAdultChoice { current ->
                    val newerDetails = order >= publishedDetails.getOrDefault(id, 0L)
                    val accepted =
                        choice?.takeIf {
                            it == current && Unread.Recommendations !in unread && order >= publishedRecommendations.getOrDefault(id, 0L)
                        }
                    if (!newerDetails) {
                        if (accepted == null) return@withAdultChoice store.holder() == begun
                        currentCoroutineContext().ensureActive()
                        return@withAdultChoice withContext(NonCancellable) {
                            store.refreshRecommendations(id, details.recommendations, accepted.enabled, begun).also { written ->
                                if (written) publishedRecommendations[id] = order
                            }
                        }
                    }
                    val kept =
                        if (accepted != null) {
                            details.copy(recommendationsIncludeAdult = accepted.enabled)
                        } else {
                            details.copy(recommendations = emptyList(), recommendationsIncludeAdult = null)
                        }
                    val missing = if (accepted != null) unread else unread + Unread.Recommendations
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        store.refresh(anime, kept, clock.instant(), missing, begun).also { written ->
                            if (written) {
                                publishedDetails[id] = order
                                if (accepted != null) publishedRecommendations[id] = order
                            }
                        }
                    }
                }
            }
        return when (stored) {
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

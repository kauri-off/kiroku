package io.github.kauri_off.kiroku

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.OwnProfile
import io.github.kauri_off.kiroku.core.repository.detailsStale
import io.github.kauri_off.kiroku.core.repository.listStale
import io.github.kauri_off.kiroku.core.repository.ownProfileStale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.Instant

// Loads a screen asks for by itself (ARCHITECTURE 7.1): when shown, and once when the connection it failed for returns.
class ShownLoads(
    scope: CoroutineScope,
    connection: Connection,
    whenShown: suspend () -> Unit,
    again: () -> Unit,
    // The screen stopped being shown, heard at once rather than after the stop timeout.
    hidden: () -> Unit = {},
    // The screen is shown again within its stop timeout, as after a rotation, when [whenShown] does not run.
    shownAgain: () -> Unit = {},
    trackOutagesWhileShown: Boolean = false,
) {
    private class Recovery

    private val watchers = MutableStateFlow(Unit)
    private val owed = MutableStateFlow<Recovery?>(null)

    // A load was repeated since the connection was last seen gone, so it is not repeated while the connection stays.
    private var spent = false

    // The stop timeouts that ran out so far: none during a hide means the next show is no new one.
    private var stops = 0

    // What the route collects while its screen is shown (CountAsShown).
    val shown: StateFlow<Unit> = watchers.asStateFlow()

    // Whether the screen is shown now: it stops being shown at once, not after the stop timeout.
    val isShowing: Boolean get() = watchers.subscriptionCount.value > 0

    init {
        scope.launch {
            var stopsAtHide = -1
            watchers.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .drop(1)
                .collect { shown ->
                    if (!shown) {
                        stopsAtHide = stops
                        hidden()
                    } else if (stops == stopsAtHide) {
                        shownAgain()
                    }
                }
        }
        scope.launch {
            WhileShown.command(watchers.subscriptionCount).collect {
                if (it == SharingCommand.START) {
                    whenShown()
                } else if (it == SharingCommand.STOP) {
                    stops++
                }
            }
        }
        scope.launch {
            val showing = watchers.subscriptionCount.map { it > 0 }.distinctUntilChanged()
            val waiting = if (trackOutagesWhileShown) showing else combine(showing, owed) { shown, owed -> shown && owed != null }
            waiting.distinctUntilChanged().collectLatest { watching ->
                if (!watching) return@collectLatest
                val online = connection.observeOnline().onEach { if (!it) spent = false }
                combine(online, owed) { online, owed -> online && owed != null }.collect { ready ->
                    if (ready && owed.value != null && isShowing && !spent) {
                        spent = true
                        owed.value = null
                        again()
                    }
                }
            }
        }
    }

    // Each load of the screen, the person's own included, starts and ends here; the error is null when it succeeded.
    fun started() {
        owed.value = null
    }

    fun ended(
        error: DataError?,
        retryAfterHiddenFailure: Boolean = false,
    ) {
        if (error != DataError.NoConnection) {
            owed.value = null
            return
        }
        if (retryAfterHiddenFailure) spent = false
        if (retryAfterHiddenFailure || owed.value == null) owed.value = Recovery()
    }

    // A load another part of the app started is owed and may fail offline: it is repeated once the connection is seen
    // to come back, not while it stays, when that part's own attempt runs.
    fun owedOffline() {
        spent = true
        owed.value = owed.value ?: Recovery()
    }

    // A load another part of the app made succeeded, so nothing is owed.
    fun loadedElsewhere() {
        owed.value = null
    }
}

// What is stale, read from the stores; a store that cannot be read holds nothing fresh.
suspend fun ListSource.stale(now: Instant): Boolean = listStale(readOrNull { observeSyncedAt().first() }, now)

suspend fun AnimeRepository.stale(
    id: AnimeId,
    known: Anime?,
    now: Instant,
): Boolean = detailsStale(readOrNull { observeAnime(id).first() } ?: known, readOrNull { observeRefreshedAt(id).first() }, now)

// The own profile, none shown or read too long ago.
suspend fun OwnProfile.profileStale(now: Instant): Boolean = ownProfileStale(readOrNull { observeOwnProfileFetchedAt().first() }, now)

suspend fun AnimeRepository.recommendationsNeedRefresh(
    id: AnimeId,
    includeAdult: Boolean,
): Boolean = readOrNull { observeDetails(id).first()?.let { it.recommendationsFor(includeAdult) == null } } == true

private inline fun <T> readOrNull(read: () -> T?): T? =
    try {
        read()
    } catch (_: DataException) {
        null
    }

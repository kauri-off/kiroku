package io.github.kauri_off.kiroku

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.detailsStale
import io.github.kauri_off.kiroku.core.repository.listStale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant

// Loads a screen asks for by itself (ARCHITECTURE 7.1): when shown, and once when the connection it failed for returns.
class ShownLoads(
    scope: CoroutineScope,
    connection: Connection,
    whenShown: suspend () -> Unit,
    again: () -> Unit,
) {
    private val watchers = MutableStateFlow(Unit)
    private val owed = MutableStateFlow(false)

    // A load was repeated since the connection was last seen gone, so it is not repeated while the connection stays.
    private var spent = false

    // What the route collects while its screen is shown (CountAsShown).
    val shown: StateFlow<Unit> = watchers.asStateFlow()

    init {
        scope.launch {
            WhileShown.command(watchers.subscriptionCount).collect { if (it == SharingCommand.START) whenShown() }
        }
        scope.launch {
            val showing = watchers.subscriptionCount.map { it > 0 }.distinctUntilChanged()
            combine(showing, owed) { showing, owed -> showing && owed }
                .distinctUntilChanged()
                .collectLatest { waiting ->
                    if (!waiting) return@collectLatest
                    connection.observeOnline().collect { online ->
                        if (!online) {
                            spent = false
                        } else if (!spent) {
                            spent = true
                            owed.value = false
                            again()
                        }
                    }
                }
        }
    }

    // Each load of the screen, the person's own included, starts and ends here; the error is null when it succeeded.
    fun started() {
        owed.value = false
    }

    fun ended(error: DataError?) {
        owed.value = error == DataError.NoConnection
    }
}

// What is stale, read from the stores; a store that cannot be read holds nothing fresh.
suspend fun ListSource.stale(now: Instant): Boolean = listStale(readOrNull { observeSyncedAt().first() }, now)

suspend fun AnimeRepository.stale(
    id: AnimeId,
    known: Anime?,
    now: Instant,
): Boolean = detailsStale(readOrNull { observeAnime(id).first() } ?: known, readOrNull { observeRefreshedAt(id).first() }, now)

private inline fun <T> readOrNull(read: () -> T?): T? =
    try {
        read()
    } catch (_: DataException) {
        null
    }

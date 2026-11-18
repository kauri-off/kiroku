package io.github.kauri_off.kiroku.notify

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.wiring.ListWork
import io.github.kauri_off.kiroku.wiring.WithoutScreen
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.Instant

// What came of one try of "Mark watched" from a notification.
sealed interface Tried {
    // The list's service holds the episode as watched, by this try or before it.
    data object Saved : Tried

    // It cannot be saved, and the title's notification says so.
    data object NotSaved : Tried

    // To be tried again this much later, which is within the waiting.
    data class Again(
        val after: Duration,
    ) : Tried

    // The tap was on another source's notification, whose list this process does not have: nothing was sent.
    data object OtherSource : Tried
}

// N9: one try of the write a notification's action asks for, and how long it waits for the next.
class MarkWatchedTries(
    private val app: WithoutScreen,
    private val clock: Clock,
    private val unexpected: (Throwable) -> Unit,
) {
    // [tried] is how many tries came before this one.
    suspend fun attempt(
        mark: MarkWatched,
        tappedAt: Instant,
        tried: Int,
    ): Tried {
        val shown = app.shown?.takeIf { it.source == mark.source } ?: return Tried.OtherSource
        val ends = tappedAt + WAITED_AT_MOST
        // A try that starts once the waiting has ended sends nothing, however late the system ran it.
        if (!clock.instant().isBefore(ends)) return notSaved(shown, mark, null)
        val anime =
            try {
                app.titleOnList(mark.title)
            } catch (_: DataException) {
                return again(shown, mark, null, waitAfter(tried), ends)
            } ?: return notSaved(shown, mark, null)
        val work =
            try {
                app.sendToList(ListWrite.MarkWatched(anime, mark.episode))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpected(e)
                return notSaved(shown, mark, anime)
            }
        return when (work) {
            is ListWork.Done ->
                when (val outcome = work.value) {
                    is Outcome.Success -> saved()
                    is Outcome.Failure ->
                        if (outcome.error in passing) again(shown, mark, anime, waitAfter(tried), ends) else notSaved(shown, mark, anime)
                }
            // Section 2.3: the next try comes when the refusal has ended, and nothing is sent before.
            is ListWork.Refused -> again(shown, mark, anime, work.remaining, ends)
            ListWork.SignedOut, ListWork.SessionExpired, ListWork.SessionUnread -> notSaved(shown, mark, anime)
        }
    }

    // The waiting ended with nothing saved, without another try: what the tap's notification is to say.
    suspend fun gaveUp(mark: MarkWatched) {
        val shown = app.shown?.takeIf { it.source == mark.source } ?: return
        val anime =
            try {
                app.titleOnList(mark.title)
            } catch (_: DataException) {
                null
            }
        notSaved(shown, mark, anime)
    }

    // N7: the title's notification goes, or names the next episode to mark without alerting.
    private suspend fun saved(): Tried {
        try {
            app.notificationRuns?.run(RunTrigger.MarkedWatched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(e)
        }
        return Tried.Saved
    }

    private suspend fun again(
        shown: EpisodeNotifications,
        mark: MarkWatched,
        anime: Anime?,
        wait: Duration,
        ends: Instant,
    ): Tried = if (clock.instant().plus(wait).isBefore(ends)) Tried.Again(wait) else notSaved(shown, mark, anime)

    private suspend fun notSaved(
        shown: EpisodeNotifications,
        mark: MarkWatched,
        anime: Anime?,
    ): Tried {
        shown.couldNotSave(mark.title, mark.episode, anime)
        return Tried.NotSaved
    }

    companion object {
        // The plan's decision on N9: no connection, a server error, a store that failed and MAL's refusal are waited out this long.
        val WAITED_AT_MOST: Duration = Duration.ofDays(1)

        private val FIRST_WAIT: Duration = Duration.ofMinutes(1)
        private val LONGEST_WAIT: Duration = Duration.ofHours(1)

        // What may answer otherwise later; anything else will not change by waiting.
        private val passing = setOf(DataError.NoConnection, DataError.ServerError, DataError.Refused, DataError.StorageError)

        // A minute after the first try, doubling to an hour.
        fun waitAfter(tried: Int): Duration = FIRST_WAIT.multipliedBy(1L shl tried.coerceIn(0, 6)).coerceAtMost(LONGEST_WAIT)
    }
}

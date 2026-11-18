package io.github.kauri_off.kiroku.notify

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.wiring.ListWork
import io.github.kauri_off.kiroku.wiring.WithoutScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

// What work on a title's notification may do to it: say that marking an episode watched could not be saved.
interface NotSavedNotice {
    // Whose list the notifications are of.
    val source: String

    // False when there is no title to say it of.
    suspend fun couldNotSave(
        title: AnimeId,
        episode: Int,
        anime: Anime?,
        said: Shown,
    ): Boolean
}

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

    // The list the tap was made on is not this process's (another source, a sign-out, another sign-in): nothing was sent or shown.
    data object Dropped : Tried
}

// N9: one try of the write a notification's action asks for, and how long it waits for the next.
class MarkWatchedTries(
    private val app: WithoutScreen,
    private val clock: Clock,
    private val unexpected: (Throwable) -> Unit,
) {
    // The sign-in whose list a tap is made in, read as it is taken: every try and its end of the waiting must find it again.
    // Null refuses the tap: nobody signed in leaves nothing to say, and a session that cannot be read says it could not be saved.
    suspend fun madeIn(mark: MarkWatched): SignInId? {
        val notice = notice(mark) ?: return null
        val session = session() ?: return null.also { notSaved(notice, mark, null) }
        return session.owner
    }

    // [madeIn] is the sign-in whose list the tap was made in; [tried], how many tries came before.
    suspend fun attempt(
        mark: MarkWatched,
        tappedAt: Instant,
        tried: Int,
        madeIn: SignInId,
    ): Tried {
        val notice = notice(mark) ?: return Tried.Dropped
        // S8: a session that cannot be read ends the waiting at once.
        val session = session() ?: return notSaved(notice, mark, null)
        // Signed out since, or signed in again (a new sign-in, also of the same user after an expiry): the tap's list has ended.
        val owner = session.owner?.takeIf { it == madeIn } ?: return Tried.Dropped
        val ends = tappedAt + WAITED_AT_MOST
        // A try that starts once the waiting has ended sends nothing, however late the system ran it.
        if (!clock.instant().isBefore(ends)) return notSaved(notice, mark, null)
        val anime =
            try {
                app.titleOnList(mark.title)
            } catch (_: DataException) {
                return again(notice, mark, null, waitAfter(tried), ends)
            } ?: return notSaved(notice, mark, null)
        val work =
            try {
                app.sendToList(ListWrite.MarkWatched(anime, mark.episode, owner))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpected(e)
                return notSaved(notice, mark, anime)
            }
        return when (work) {
            is ListWork.Done ->
                when (val error = (work.value as? Outcome.Failure)?.error) {
                    null -> saved()
                    // Another sign-in's list now, or none: the tap is not its user's to save there.
                    DataError.ListEnded -> Tried.Dropped
                    in passing -> again(notice, mark, anime, waitAfter(tried), ends)
                    else -> notSaved(notice, mark, anime)
                }
            // Section 2.3: the next try comes when the refusal has ended, and nothing is sent before.
            is ListWork.Refused -> again(notice, mark, anime, work.remaining, ends)
            ListWork.SignedOut -> Tried.Dropped
            ListWork.SessionExpired, ListWork.SessionUnread -> notSaved(notice, mark, anime)
        }
    }

    // The waiting ended with nothing saved, without another try: what the tap's notification is to say, if it is still its user's.
    suspend fun gaveUp(
        mark: MarkWatched,
        madeIn: SignInId,
    ) {
        val notice = notice(mark) ?: return
        if (session()?.owner != madeIn) return
        val anime =
            try {
                app.titleOnList(mark.title)
            } catch (_: DataException) {
                null
            }
        notSaved(notice, mark, anime)
    }

    private fun notice(mark: MarkWatched): NotSavedNotice? = app.notSaved?.takeIf { it.source == mark.source }

    // The session as stored; null when it cannot be read.
    private suspend fun session(): Session? = withTimeoutOrNull(SESSION_READ) { app.session() }

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
        notice: NotSavedNotice,
        mark: MarkWatched,
        anime: Anime?,
        wait: Duration,
        ends: Instant,
    ): Tried = if (clock.instant().plus(wait).isBefore(ends)) Tried.Again(wait) else notSaved(notice, mark, anime)

    private suspend fun notSaved(
        notice: NotSavedNotice,
        mark: MarkWatched,
        anime: Anime?,
    ): Tried {
        notice.couldNotSave(mark.title, mark.episode, anime, mark.said)
        return Tried.NotSaved
    }

    companion object {
        // The plan's decision on N9: no connection, a server error, a store that failed and MAL's refusal are waited out this long.
        val WAITED_AT_MOST: Duration = Duration.ofDays(1)

        private val FIRST_WAIT: Duration = Duration.ofMinutes(1)
        private val LONGEST_WAIT: Duration = Duration.ofHours(1)
        private val SESSION_READ = 5.seconds

        // What may answer otherwise later; anything else will not change by waiting.
        private val passing = setOf(DataError.NoConnection, DataError.ServerError, DataError.Refused, DataError.StorageError)

        // A minute after the first try, doubling to an hour.
        fun waitAfter(tried: Int): Duration = FIRST_WAIT.multipliedBy(1L shl tried.coerceIn(0, 6)).coerceAtMost(LONGEST_WAIT)
    }
}

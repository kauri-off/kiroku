package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.data.network.MalHttp
import java.time.Duration

// What came of work without a screen.
sealed interface BackgroundRun<out T> {
    data class Ran<out T>(
        val value: T,
    ) : BackgroundRun<T>
}

// Why the work was not run, and nothing was sent to MAL for it.
sealed interface HeldBack : BackgroundRun<Nothing> {
    data object SignedOut : HeldBack

    // S6: the session is expired.
    data object Expired : HeldBack

    // The token file could not be read, so nobody is known to be signed in.
    data object TokensUnread : HeldBack

    // MAL refused a request, and its back-off lasts this long yet (section 2.3).
    data class Refused(
        val remaining: Duration,
    ) : HeldBack
}

// The one way in to MAL for work that runs without a screen: an alarm, periodic work, a notification's action.
class MalBackground(
    private val http: MalHttp,
    private val session: MalSession,
) {
    // Renews the tokens when they are due (S7), then runs [work], which alone may call MAL.
    suspend fun <T> run(work: suspend () -> T): BackgroundRun<T> {
        held()?.let { return it }
        session.renewIfDue()
        held()?.let { return it }
        return BackgroundRun.Ran(work())
    }

    private suspend fun held(): HeldBack? =
        when (session.current()) {
            null -> HeldBack.TokensUnread
            Session.SignedOut -> HeldBack.SignedOut
            is Session.Expired -> HeldBack.Expired
            is Session.SignedIn -> http.refusedFor().takeUnless { it.isZero }?.let(HeldBack::Refused)
        }
}

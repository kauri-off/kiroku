package io.github.kauri_off.kiroku.wiring

import android.content.Context
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.notify.NotSavedNotice
import kotlinx.coroutines.flow.first
import java.time.Duration

// What came of work on the list that no screen asked for.
sealed interface ListWork<out T> {
    data class Done<out T>(
        val value: T,
    ) : ListWork<T>

    // Why the work was not begun; nothing was sent for it.
    sealed interface HeldBack : ListWork<Nothing>

    data object SignedOut : HeldBack

    // S6: the session is expired, which only a sign-in changes.
    data object SessionExpired : HeldBack

    // The session could not be read, so nobody is known to be signed in.
    data object SessionUnread : HeldBack

    // The list's service refused a request and is left alone this long yet (section 2.3).
    data class Refused(
        val remaining: Duration,
    ) : HeldBack
}

// All of the app that code running without a screen is given: it hands out no list and no other way to the list's service.
interface WithoutScreen {
    // What the alarm, the moments the system starts the app at and its opening run; a graph without notifications has none.
    val notificationRuns: NotificationRuns?

    // Downloads the list. Due tokens are renewed first (S7), so its caller stays until it returns.
    suspend fun syncList(): ListWork<Outcome<Unit>>

    // Writes to the list and waits for the service's answer (N9), with the tokens renewed first as for a download.
    suspend fun sendToList(write: ListWrite): ListWork<Outcome<Unit>>

    // Asks the airing source again for the tracked titles whose airing data is stale; the list's service has no part in it.
    suspend fun updateAiring(): Outcome<Unit>

    // The title as the list on the device has it, with nothing sent; null when it is not on the list. Throws a DataException when the list cannot be read.
    suspend fun titleOnList(id: AnimeId): Anime?

    // The session as stored, with nothing sent; it waits while the store cannot be read.
    suspend fun session(): Session

    // What may tell, in a title's notification, that marking an episode watched could not be saved; none in a graph that posts none.
    val notSaved: NotSavedNotice?
}

// A process that runs work without a screen: the application.
interface WithoutScreenOwner {
    val withoutScreen: WithoutScreen
}

val Context.withoutScreen: WithoutScreen? get() = (applicationContext as? WithoutScreenOwner)?.withoutScreen

// For a graph whose list reaches no service that could hold the work back: the work at once.
class WorkAtOnce(
    private val list: () -> ListRepository,
    private val airing: () -> AiringRepository,
    private val runs: () -> NotificationRuns?,
    private val sessions: () -> SessionRepository,
    override val notSaved: NotSavedNotice? = null,
) : WithoutScreen {
    override val notificationRuns: NotificationRuns? get() = runs()

    override suspend fun syncList(): ListWork<Outcome<Unit>> = ListWork.Done(list().sync())

    override suspend fun sendToList(write: ListWrite): ListWork<Outcome<Unit>> = ListWork.Done(list().send(write))

    override suspend fun updateAiring(): Outcome<Unit> = airing().refreshStale()

    override suspend fun titleOnList(id: AnimeId): Anime? = list().observeEntry(id).first()?.anime

    override suspend fun session(): Session = sessions().observeSession().first()
}

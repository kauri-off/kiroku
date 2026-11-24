package io.github.kauri_off.kiroku.data.profile

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.OwnProfile
import io.github.kauri_off.kiroku.data.db.RoomProfileStore
import io.github.kauri_off.kiroku.data.db.StoredProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant

// The own-profile part of the user's repository: MAL's last whole answer, shown to the sign-in it was read as, expired too (S6).
class MalOwnProfile(
    private val profiles: MalProfiles,
    private val store: RoomProfileStore,
    private val sessions: Flow<Session>,
    private val clock: Clock,
    private val unexpected: (Throwable) -> Unit,
) : OwnProfile {
    override fun observeOwnProfile(): Flow<UserProfile?> = observeOwn { it.profile }

    override fun observeOwnProfileFetchedAt(): Flow<Instant?> = observeOwn { it.fetchedAt }

    // S9, at a sign-out: every profile but the session's leaves the database.
    suspend fun forget() = store.forgetAllBut { sessions.first().signIn }

    private fun <T> observeOwn(value: (StoredProfile) -> T): Flow<T?> =
        combine(sessions, store.observe()) { session, stored -> stored?.takeIf { it.owner == session.signIn }?.let(value) }
            .distinctUntilChanged()
            .catch { e ->
                if (e !is Exception || e is CancellationException) throw e
                unexpected(e)
                throw DataException(DataError.StorageError, e)
            }

    // An answer for a sign-in that is no longer the session's is not stored: its list has ended.
    override suspend fun refreshOwnProfile(expectedOwner: SignInId?): Outcome<Unit> {
        val owner = sessions.first().signIn
        if (expectedOwner != null && owner != expectedOwner) return Outcome.Failure(DataError.ListEnded)
        if (owner == null) return Outcome.Failure(DataError.SessionExpired)
        val answer = profiles.own(owner)
        if (answer !is Outcome.Success) return answer as Outcome.Failure
        val stored =
            try {
                store.replace(StoredProfile(owner, answer.value, clock.instant())) { sessions.first().signIn == owner }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unexpected(e)
                return Outcome.Failure(DataError.StorageError)
            }
        return if (stored) Outcome.Success(Unit) else Outcome.Failure(DataError.ListEnded)
    }
}

private val Session.signIn: SignInId?
    get() =
        when (this) {
            Session.SignedOut -> null
            is Session.SignedIn -> signIn
            is Session.Expired -> signIn
        }

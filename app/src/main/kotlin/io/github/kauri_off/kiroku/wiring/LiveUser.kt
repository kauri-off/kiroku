package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.UserRepository
import io.github.kauri_off.kiroku.core.user.RemoteUserLists
import io.github.kauri_off.kiroku.core.user.UserListKeeper
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.data.db.RoomProfileStore
import io.github.kauri_off.kiroku.data.list.MalUserLists
import io.github.kauri_off.kiroku.data.profile.MalOwnProfile
import io.github.kauri_off.kiroku.data.profile.MalProfiles
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.time.Instant

// The profiles of a live source: the signed-in user's in its own database, other users' lists read with the client id alone.
internal fun liveUser(
    parts: LiveParts,
    session: MalSession,
    own: (LiveParts, MalSession) -> MalOwnProfile = ::ownProfileOf,
): LiveUser = LiveUser(MalUserLists(parts.http), parts.scope, parts.unexpected) { own(parts, session) }

// MAL's answer about the signed-in user, kept in the source's own database.
internal fun ownProfileOf(
    parts: LiveParts,
    session: MalSession,
): MalOwnProfile {
    val store = RoomProfileStore(parts.database, Dispatchers.IO, parts.unexpected)
    return MalOwnProfile(MalProfiles(parts.http, session), store, session.observeSession(), parts.clock, parts.unexpected)
}

// The own profile and its database are built at their first use, off the main thread; telling two names apart builds nothing.
internal class LiveUser(
    lists: RemoteUserLists,
    private val scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
    own: () -> MalOwnProfile,
) : UserRepository {
    private val keeper = UserListKeeper(lists, scope, unexpected)
    private val built = lazy(own)

    private suspend fun own(): MalOwnProfile = built.onFirstUse(scope)

    override fun observeOwnProfile(): Flow<UserProfile?> = flow { emitAll(own().observeOwnProfile()) }

    override fun observeOwnProfileFetchedAt(): Flow<Instant?> = flow { emitAll(own().observeOwnProfileFetchedAt()) }

    override suspend fun refreshOwnProfile() = own().refreshOwnProfile()

    override fun observeList(name: String): Flow<UserListState> = keeper.observeList(name)

    override suspend fun refreshList(name: String) = keeper.refreshList(name)

    override fun sameUser(
        a: String,
        b: String,
    ): Boolean = keeper.sameUser(a, b)

    // S9, once the session is cleared: no list stays held, and no profile stays in the database, asked for or not.
    suspend fun forget() {
        keeper.forget()
        try {
            own().forget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unexpected(e)
        }
    }
}

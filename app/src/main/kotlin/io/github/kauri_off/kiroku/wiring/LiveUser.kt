package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.UserRepository
import io.github.kauri_off.kiroku.core.user.UserListState
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The profiles of a live source: nobody's is known and none can be fetched yet.
@Suppress("UNUSED_PARAMETER")
internal fun liveUser(
    parts: LiveParts,
    session: MalSession,
): UserRepository =
    object : UserRepository {
        override fun observeOwnProfile(): Flow<UserProfile?> = flowOf(null)

        override suspend fun refreshOwnProfile(): Outcome<Unit> = Outcome.Failure(DataError.SessionExpired)

        override fun observeList(name: String): Flow<UserListState> = flowOf(UserListState.None(DataError.ServerError))

        override suspend fun refreshList(name: String): Outcome<Unit> = Outcome.Failure(DataError.ServerError)

        override fun sameUser(
            a: String,
            b: String,
        ): Boolean = a == b
    }

package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.UserListLoad
import io.github.kauri_off.kiroku.core.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The profiles of a live source: nobody's is known and none can be fetched yet.
internal fun liveUser(): UserRepository =
    object : UserRepository {
        override fun observeOwnProfile(): Flow<UserProfile?> = flowOf(null)

        override suspend fun refreshOwnProfile(): Outcome<Unit> = Outcome.Failure(DataError.SessionExpired)

        override fun loadList(userName: String): Flow<UserListLoad> = flowOf(UserListLoad.Failed(DataError.ServerError))
    }

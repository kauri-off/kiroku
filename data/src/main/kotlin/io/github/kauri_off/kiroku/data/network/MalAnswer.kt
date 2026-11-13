package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome

// How a request to MAL ended.
internal sealed interface MalAnswer<out T> {
    data class Success<out T>(
        val value: T,
    ) : MalAnswer<T>

    data class Failed(
        val error: DataError,
    ) : MalAnswer<Nothing>

    // MAL did not accept the request's credentials; the owner of the tokens decides what follows.
    data object Rejected : MalAnswer<Nothing>
}

// Only for a request that carried the client id alone, where no other credentials can be tried.
internal fun <T> MalAnswer<T>.clientIdOutcome(): Outcome<T> =
    when (this) {
        is MalAnswer.Success -> Outcome.Success(value)
        is MalAnswer.Failed -> Outcome.Failure(error)
        MalAnswer.Rejected -> Outcome.Failure(DataError.InvalidRequest)
    }

internal const val AUTHORIZATION = "Authorization"

// The value of [AUTHORIZATION] for a request made as the signed-in user.
internal fun bearer(accessToken: String): String = "Bearer $accessToken"

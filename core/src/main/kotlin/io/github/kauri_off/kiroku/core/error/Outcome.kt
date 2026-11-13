package io.github.kauri_off.kiroku.core.error

sealed interface DataError {
    data object NoConnection : DataError

    data object ServerError : DataError

    // The device's own store failed.
    data object StorageError : DataError

    data object Refused : DataError

    data object InvalidRequest : DataError

    data object SessionExpired : DataError

    // An Undo or a Retry of a list that has ended: its sign-in is no longer the session's.
    data object ListEnded : DataError

    data object NotFound : DataError

    data object PrivateList : DataError

    data object UnknownUser : DataError
}

// Ends a flow that cannot go on, where no outcome can be returned.
class DataException(
    val error: DataError,
    cause: Throwable? = null,
) : Exception(error.toString(), cause)

sealed interface Outcome<out T> {
    data class Success<out T>(
        val value: T,
    ) : Outcome<T>

    data class Failure(
        val error: DataError,
    ) : Outcome<Nothing>
}

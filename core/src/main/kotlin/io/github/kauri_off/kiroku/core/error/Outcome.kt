package io.github.kauri_off.kiroku.core.error

sealed interface DataError {
    data object NoConnection : DataError

    data object ServerError : DataError

    // The device's own store failed.
    data object StorageError : DataError

    data object Refused : DataError

    data object InvalidRequest : DataError

    data object SessionExpired : DataError

    data object NotFound : DataError

    data object PrivateList : DataError

    data object UnknownUser : DataError
}

sealed interface Outcome<out T> {
    data class Success<out T>(
        val value: T,
    ) : Outcome<T>

    data class Failure(
        val error: DataError,
    ) : Outcome<Nothing>
}

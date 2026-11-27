package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.UserProfile

sealed interface NamedListState<out E> {
    val user: UserProfile?

    data class Reading<E>(
        val entries: List<E>,
        val total: Int?,
        override val user: UserProfile?,
    ) : NamedListState<E>

    data class Whole<E>(
        val entries: List<E>,
        override val user: UserProfile?,
        val again: UserListProgress? = null,
    ) : NamedListState<E>

    data class None(
        val error: DataError,
        override val user: UserProfile? = null,
    ) : NamedListState<Nothing>
}

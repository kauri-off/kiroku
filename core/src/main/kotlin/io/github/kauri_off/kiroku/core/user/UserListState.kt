package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.UserProfile

// What the app holds of the list of a user the person named: one value for every screen that shows that user.
sealed interface UserListState {
    // What the source said of the user besides the list, which may be nothing.
    val user: UserProfile?

    // No list is held and one is being read: the entries so far, in the list's order.
    data class Reading(
        val entries: List<ListEntry>,
        val total: Int?,
        override val user: UserProfile?,
    ) : UserListState

    // The last list read whole, each title once; [again] while it is being read again.
    data class Whole(
        val entries: List<ListEntry>,
        override val user: UserProfile?,
        val again: UserListProgress? = null,
    ) : UserListState

    // No list: it is private, nobody has the name, or the read failed with none held (section 2.3).
    data class None(
        val error: DataError,
        override val user: UserProfile? = null,
    ) : UserListState
}

// How far a read is, and the size of the whole list where the source names it.
data class UserListProgress(
    val loaded: Int,
    val total: Int?,
)

// The source's answer about the user, which a read gives again; any other failure says nothing of the list.
val DataError.ofUser: Boolean get() = this == DataError.PrivateList || this == DataError.UnknownUser

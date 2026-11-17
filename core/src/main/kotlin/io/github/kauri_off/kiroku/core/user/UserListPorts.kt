package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.UserProfile

// Where the list of a user the person named is read. A read ends in an outcome, never an exception.
interface RemoteUserLists {
    // From the start, in order, the next part asked once [part] returned and none when cancelled; a success: that was all.
    suspend fun read(
        name: String,
        part: suspend (UserListPart) -> Unit,
    ): Outcome<Unit>

    // Whether the two names are one user's as the source takes names: an equivalence, answered from the names alone.
    fun sameUser(
        a: String,
        b: String,
    ): Boolean
}

// What one answer gave of a user's list.
data class UserListPart(
    val entries: List<ListEntry>,
    // How many entries the whole list has, where the source names it.
    val total: Int? = null,
    // What the source said of the user besides the list, which may be nothing.
    val user: UserProfile? = null,
) {
    init {
        require(total == null || total >= 0)
    }
}

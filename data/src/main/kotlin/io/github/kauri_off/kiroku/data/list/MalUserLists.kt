package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.user.RemoteUserLists
import io.github.kauri_off.kiroku.core.user.UserListPart
import io.github.kauri_off.kiroku.data.network.MalHttp

// Other users' lists on MAL, a page a part, read with the client id alone; making it builds nothing of the client.
class MalUserLists(
    http: MalHttp,
) : RemoteUserLists {
    private val lists = MalLists(http)

    // MAL gives no total and nothing of the user with a list.
    override suspend fun read(
        name: String,
        part: suspend (UserListPart) -> Unit,
    ): Outcome<Unit> = lists.pages(ListUser.Named(name)) { part(UserListPart(it)) }

    // MAL takes a name in any case of its letters as the same user (list_public_another_case); nothing else is recorded.
    override fun sameUser(
        a: String,
        b: String,
    ): Boolean = sameMalUser(a, b)
}

internal fun sameMalUser(
    a: String,
    b: String,
): Boolean = a.length == b.length && a.indices.all { folded(a[it]) == folded(b[it]) }

private fun folded(c: Char): Char = if (c in 'A'..'Z') c.lowercaseChar() else c

package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.repository.OwnProfile
import io.github.kauri_off.kiroku.core.repository.UserLists
import io.github.kauri_off.kiroku.core.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map

class UserListKeeper(
    private val source: RemoteUserLists,
    scope: CoroutineScope,
    unexpected: (Throwable) -> Unit,
    held: Int = USERS_HELD,
) : UserLists {
    private val keeper =
        NamedListKeeper<ListEntry, AnimeId>(
            { name, part -> source.read(name) { part(NamedListPart(it.entries, it.total, it.user)) } },
            source::sameUser,
            { it.anime.id },
            scope,
            unexpected,
            held,
        )

    internal val holding get() = keeper.holding

    override fun observeList(name: String) =
        keeper.observeList(name).map {
            when (it) {
                is NamedListState.Reading -> UserListState.Reading(it.entries, it.total, it.user)
                is NamedListState.Whole -> UserListState.Whole(it.entries, it.user, it.again)
                is NamedListState.None -> UserListState.None(it.error, it.user)
            }
        }

    override suspend fun refreshList(name: String) = keeper.refreshList(name)

    override fun sameUser(
        a: String,
        b: String,
    ): Boolean = source.sameUser(a, b)

    fun forget() = keeper.forget()

    fun beside(own: OwnProfile): UserRepository = object : UserRepository, OwnProfile by own, UserLists by this@UserListKeeper {}

    companion object {
        const val USERS_HELD = 3
    }
}

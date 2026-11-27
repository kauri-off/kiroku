package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

interface PublicMangaLists {
    fun observeList(name: String): Flow<NamedListState<MangaListEntry>>

    suspend fun refreshList(name: String): Outcome<Unit>
}

interface RemoteMangaLists {
    suspend fun read(
        name: String,
        part: suspend (List<MangaListEntry>) -> Unit,
    ): Outcome<Unit>

    fun sameUser(
        a: String,
        b: String,
    ): Boolean
}

class MangaListKeeper(
    source: RemoteMangaLists,
    scope: CoroutineScope,
    unexpected: (Throwable) -> Unit,
) : PublicMangaLists {
    private val keeper =
        NamedListKeeper<MangaListEntry, MangaId>(
            { name, part -> source.read(name) { part(NamedListPart(it)) } },
            source::sameUser,
            MangaListEntry::id,
            scope,
            unexpected,
            UserListKeeper.USERS_HELD,
        )

    override fun observeList(name: String) = keeper.observeList(name)

    override suspend fun refreshList(name: String) = keeper.refreshList(name)

    fun forget() = keeper.forget()
}

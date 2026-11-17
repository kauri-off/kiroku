package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import kotlinx.coroutines.test.TestScope

// The keeper's tests run over a source that keeps the port's word.
class ScriptedUserListsTest : RemoteUserListsContract() {
    override fun subject(scope: TestScope): Subject {
        val entries = Fixtures.reference.entries
        val source = ScriptedUserLists()
        source.has("viewer", entries.chunked(7).map { UserListPart(it, total = entries.size) })
        source.private("quiet")
        return Subject(
            source = source,
            public = "viewer",
            titles = entries.map { it.anime.id },
            private = "quiet",
            nobody = "nobody",
            asked = { source.asked.size },
            failing = { failing ->
                DataError.ServerError.also { source.failure = it.takeIf { failing } }
            },
            alias = "Viewer",
        )
    }
}

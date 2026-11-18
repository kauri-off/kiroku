package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureUser
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.user.RemoteUserListsContract
import kotlinx.coroutines.test.TestScope

class DemoUserListsTest : RemoteUserListsContract() {
    override fun subject(scope: TestScope): Subject {
        val fixtures = Fixtures.reference
        val demo = Demo(fixtures, scope.backgroundScope)
        return Subject(
            source = demo.userLists,
            public = fixtures.otherUserName,
            titles = fixtures.otherUserList.map { it.anime.id },
            private = fixtures.privateUserName,
            nobody = FixtureUser.unknownName,
            asked = { demo.userLists.requests.size },
            failing = { failing ->
                demo.server.offline.value = failing
                DataError.NoConnection
            },
            alias = fixtures.otherUserName.uppercase(),
        )
    }
}

package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.list.ListEngineContract
import io.github.kauri_off.kiroku.core.list.LocalList
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Over Room's own pool, which no build uses: there a call of the store ends where it began, as two tests need.
@RunWith(RobolectricTestRunner::class)
class RoomListEngineInlineTest : ListEngineContract() {
    @get:Rule
    val databases = TestDatabases()

    override fun local(scope: TestScope): LocalList =
        RoomLocalList(databases.open(scope, inline = true), scope.backgroundScope, scope.dispatcher)
}

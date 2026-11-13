package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.list.ListEngineContract
import io.github.kauri_off.kiroku.core.list.LocalList
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomListEngineTest : ListEngineContract() {
    @get:Rule
    val databases = TestDatabases()

    override fun local(scope: TestScope): LocalList = RoomLocalList(databases.open(scope), scope.backgroundScope, scope.dispatcher)
}

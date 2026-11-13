package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.list.ListEngineContract
import io.github.kauri_off.kiroku.core.list.LocalList
import kotlinx.coroutines.test.TestScope
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.EmptyCoroutineContext

// Over the pool the app uses. Its transactions need a thread of their own, so the tests that dispatch nothing are
// skipped here and run in [RoomListEngineInlineTest].
@RunWith(RobolectricTestRunner::class)
class RoomListEngineTest : ListEngineContract() {
    @get:Rule
    val databases = TestDatabases()

    override fun local(scope: TestScope): LocalList {
        assumeTrue(scope.dispatcher.isDispatchNeeded(EmptyCoroutineContext))
        return RoomLocalList(databases.open(scope), scope.backgroundScope, scope.dispatcher)
    }
}

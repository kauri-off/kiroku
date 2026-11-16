package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.airing.AiringEngineContract
import io.github.kauri_off.kiroku.core.airing.AiringStore
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomAiringEngineTest : AiringEngineContract() {
    @get:Rule
    val databases = TestDatabases()

    override fun store(scope: TestScope): AiringStore = RoomAiringStore(databases.open(scope), scope.dispatcher) { throw it }
}

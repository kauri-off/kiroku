package io.github.kauri_off.kiroku.scenario

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.wiring.foregroundScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScenariosTest {
    private val normal = Demo.latency

    private fun Demo.switches() =
        listOf(
            server.offline.value,
            server.sessionExpired.value,
            server.latency != normal,
            server.refusingWrites.value,
            server.failingWrites.value,
            store.failingWrites.value,
            store.unreadable.value,
        )

    @Test
    fun `each scenario drives the demo's server and store, and they combine`() =
        runTest {
            val demo = Demo(Fixtures.reference, backgroundScope, Demo.latency)
            val scenarios = Scenarios(demo, backgroundScope)
            assertEquals(List(7) { false }, demo.switches())

            scenarios.set(
                Scenario(offline = true, sessionExpired = true, slow = true, writes = WriteFault.Refused, store = StoreFault.Unreadable),
            )
            assertEquals(listOf(true, true, true, true, false, false, true), demo.switches())
            assertEquals(Scenarios.SLOW, demo.server.latency)

            scenarios.set(Scenario(writes = WriteFault.ServerError, store = StoreFault.WritesFail))
            assertEquals(listOf(false, false, false, false, true, true, false), demo.switches())

            scenarios.set(Scenario())
            assertEquals(List(7) { false }, demo.switches())
            assertFalse(scenarios.scenario.value.active)
        }

    @Test
    fun `offline, a sync fails with no connection, and the list stays`() =
        runTest {
            val demo = Demo(Fixtures.reference, backgroundScope)
            Scenarios(demo, backgroundScope).set(Scenario(offline = true))
            assertEquals(Outcome.Failure(DataError.NoConnection), demo.list.sync())
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
        }

    @Test
    fun `reset turns every scenario off and brings the demo's data back`() =
        runTest {
            val scope = foregroundScope()
            val demo = Demo(Fixtures.reference, scope, Demo.latency)
            val scenarios = Scenarios(demo, scope)
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            advanceUntilIdle()
            scenarios.set(Scenario(slow = true, sessionExpired = true, store = StoreFault.WritesFail))

            scenarios.reset()
            assertTrue(scenarios.resetting.value)
            advanceUntilIdle()

            assertEquals(Scenario(), scenarios.scenario.value)
            assertFalse(scenarios.resetting.value)
            assertEquals(List(7) { false }, demo.switches())
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
            scope.cancel()
        }

    @Test
    fun `the marker names every scenario that is on, in the panel's order`() {
        val all = Scenario(offline = true, sessionExpired = true, slow = true, writes = WriteFault.Refused, store = StoreFault.Unreadable)
        assertEquals(5, all.names().size)
        assertEquals(emptyList<Int>(), Scenario().names())
        assertEquals(2, Scenario(writes = WriteFault.ServerError, store = StoreFault.WritesFail).names().size)
    }
}

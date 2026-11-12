package io.github.kauri_off.kiroku.scenario

import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
import io.github.kauri_off.kiroku.wiring.foregroundScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
            assertEquals(ResetState.Running, scenarios.reset.value)
            advanceUntilIdle()

            assertEquals(Scenario(), scenarios.scenario.value)
            assertEquals(ResetState.Idle, scenarios.reset.value)
            assertEquals(List(7) { false }, demo.switches())
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
            scope.cancel()
        }

    @Test
    fun `reset waits for a save on its way, answered under the scenarios it was made in, and keeps the switches meanwhile`() =
        runTest {
            val scope = foregroundScope()
            val demo = Demo(Fixtures.reference, scope, Demo.latency)
            val scenarios = Scenarios(demo, scope)
            val failures = mutableListOf<WriteFailure>()
            scope.launch { demo.list.observeWriteFailures().collect { failures += it } }
            val made = Scenario(offline = true, slow = true)
            scenarios.set(made)
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            runCurrent()

            scenarios.reset()
            assertEquals(ResetState.WaitingForSave, scenarios.reset.value)
            scenarios.set(Scenario(writes = WriteFault.Refused))
            advanceTimeBy(Scenarios.SLOW / 2)
            assertEquals(made, scenarios.scenario.value)
            assertTrue(demo.server.offline.value)

            advanceUntilIdle()
            assertEquals(listOf(DataError.NoConnection), failures.map { it.error })
            assertEquals(ResetState.Idle, scenarios.reset.value)
            assertEquals(List(7) { false }, demo.switches())
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
            scope.cancel()
        }

    @Test
    fun `reset says so when an edit a failing store left unconfirmed is dropped, and the list still comes back`() =
        runTest {
            val scope = foregroundScope()
            val demo = Demo(Fixtures.reference, scope, Demo.latency)
            val scenarios = Scenarios(demo, scope)
            scenarios.set(Scenario(slow = true))
            demo.list.write(ListWrite.MarkNextWatched(FixtureAnime.exactToWatch))
            runCurrent()
            scenarios.set(Scenario(slow = true, store = StoreFault.WritesFail))
            advanceTimeBy(Scenarios.SLOW * 2)
            scenarios.set(Scenario())

            scenarios.reset()
            advanceUntilIdle()
            assertEquals(ResetState.SaveDropped, scenarios.reset.value)
            assertEquals(Fixtures.reference.entries, demo.list.observeEntries().first())
            scenarios.set(Scenario(offline = true))
            assertEquals(ResetState.Idle, scenarios.reset.value)
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

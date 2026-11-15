package io.github.kauri_off.kiroku.scenario

import android.content.Context
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

enum class WriteFault { None, Refused, ServerError }

enum class StoreFault { None, WritesFail, Unreadable }

data class Scenario(
    val offline: Boolean = false,
    val sessionExpired: Boolean = false,
    val slow: Boolean = false,
    val writes: WriteFault = WriteFault.None,
    val store: StoreFault = StoreFault.None,
) {
    val active: Boolean get() = this != Scenario()
}

enum class ResetState {
    Idle,
    WaitingForSave,
    Running,
    SaveDropped,
    Failed,
    ;

    val busy: Boolean get() = this == WaitingForSave || this == Running
}

// What the panel's switches act on: the demo's MAL and store, or the sandbox's MAL.
interface ScenarioSubject {
    // The sandbox's: it has no store of its own to fail.
    val sandbox: Boolean

    fun apply(scenario: Scenario)

    val saving: Boolean

    suspend fun awaitSaves(): Boolean

    suspend fun reset(): Outcome<Unit>
}

private class DemoScenarios(
    private val demo: Demo,
) : ScenarioSubject {
    private val normal = demo.server.latency

    override val sandbox = false

    override fun apply(scenario: Scenario) {
        with(demo.server) {
            offline.value = scenario.offline
            sessionExpired.value = scenario.sessionExpired
            latency = if (scenario.slow) Scenarios.SLOW else normal
            refusingWrites.value = scenario.writes == WriteFault.Refused
            failingWrites.value = scenario.writes == WriteFault.ServerError
        }
        demo.store.unreadable.value = scenario.store == StoreFault.Unreadable
        demo.store.failingWrites.value = scenario.store == StoreFault.WritesFail
    }

    override val saving get() = demo.saving

    override suspend fun awaitSaves() = demo.awaitSaves()

    override suspend fun reset() = demo.reset()
}

// The failures of the source the app runs on, switched while it runs; a new process starts with every one off.
class Scenarios(
    private val subject: ScenarioSubject,
    private val scope: CoroutineScope,
) {
    constructor(demo: Demo, scope: CoroutineScope) : this(DemoScenarios(demo), scope)

    val sandbox: Boolean get() = subject.sandbox

    private val current = MutableStateFlow(Scenario())
    private val resets = MutableStateFlow(ResetState.Idle)

    val scenario: StateFlow<Scenario> = current.asStateFlow()

    val reset: StateFlow<ResetState> = resets.asStateFlow()

    fun set(scenario: Scenario) {
        if (resets.value.busy) return
        resets.value = ResetState.Idle
        apply(scenario)
    }

    private fun apply(scenario: Scenario) {
        subject.apply(scenario)
        current.value = scenario
    }

    // An edit on its way to MAL is answered under the scenarios it was made in; then all are off and the list is back.
    fun reset() {
        if (resets.value.busy) return
        resets.value = if (subject.saving) ResetState.WaitingForSave else ResetState.Running
        scope.launch {
            resets.value =
                try {
                    val settled = subject.awaitSaves()
                    resets.value = ResetState.Running
                    apply(Scenario())
                    when {
                        subject.reset() is Outcome.Failure -> ResetState.Failed
                        !settled -> ResetState.SaveDropped
                        else -> ResetState.Idle
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ResetState.Failed
                }
        }
    }

    companion object {
        val SLOW = 3.seconds
    }
}

// A graph whose failures the panel switches.
interface ScenariosOwner {
    val scenarios: Scenarios
}

// The scenarios of the demo or the sandbox, or null on the real account.
val Context.scenarios: Scenarios? get() = (appGraph as? ScenariosOwner)?.scenarios

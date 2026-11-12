package io.github.kauri_off.kiroku.scenario

import io.github.kauri_off.kiroku.core.demo.Demo
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

// The demo's failures, switched while the app runs. They live as long as the process: a new one starts the demo
// from the fixtures with every scenario off.
class Scenarios(
    private val demo: Demo,
    private val scope: CoroutineScope,
) {
    private val normal = demo.server.latency
    private val current = MutableStateFlow(Scenario())
    private val resets = MutableStateFlow(false)

    val scenario: StateFlow<Scenario> = current.asStateFlow()

    val resetting: StateFlow<Boolean> = resets.asStateFlow()

    fun set(scenario: Scenario) {
        with(demo.server) {
            offline.value = scenario.offline
            sessionExpired.value = scenario.sessionExpired
            latency = if (scenario.slow) SLOW else normal
            refusingWrites.value = scenario.writes == WriteFault.Refused
            failingWrites.value = scenario.writes == WriteFault.ServerError
        }
        demo.store.unreadable.value = scenario.store == StoreFault.Unreadable
        demo.store.failingWrites.value = scenario.store == StoreFault.WritesFail
        current.value = scenario
    }

    // Every scenario off, and the demo's data as it was when the process started.
    fun reset() {
        if (!resets.compareAndSet(expect = false, update = true)) return
        current.value = Scenario()
        scope.launch {
            try {
                demo.reset()
            } finally {
                resets.value = false
            }
        }
    }

    companion object {
        val SLOW = 3.seconds
    }
}

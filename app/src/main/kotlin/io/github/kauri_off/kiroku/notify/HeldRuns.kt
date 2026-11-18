package io.github.kauri_off.kiroku.notify

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// A broadcast is held for its run for less than the system gives its receiver: a minute, or ten seconds when it is a foreground one.
val HELD_FOR = 30.seconds
val HELD_FOR_FOREGROUND = 8.seconds

// Starts the notification engine's runs where no screen asks for one, and at the app's opening.
class HeldRuns(
    private val scope: CoroutineScope,
    // The engine, built at its first use and off the main thread; none in a graph without notifications.
    private val runs: () -> NotificationRuns?,
) {
    // Calls [done] once: when the run has ended or cannot start, or after [heldFor], past which the run goes on with nothing holding the process.
    fun start(
        trigger: RunTrigger,
        heldFor: Duration = HELD_FOR,
        done: () -> Unit = {},
    ): Job = held(heldFor, done) { runs()?.run(trigger) }

    // Other work a broadcast's receiver holds the process for, in the application's scope, let go as a run is.
    fun held(
        heldFor: Duration,
        done: () -> Unit,
        work: suspend () -> Unit,
    ): Job = scope.launch { withTimeoutOrNull(heldFor) { work() } }.also { it.invokeOnCompletion { done() } }

    // A run each time the app comes to the foreground, which is also the first moment after a force-stop.
    fun atEachOpening(app: Lifecycle) {
        app.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    start(RunTrigger.AppOpened)
                }
            },
        )
    }
}

// A process that runs the notification engine: the application.
interface HeldRunsOwner {
    val heldRuns: HeldRuns
}

val Context.heldRuns: HeldRuns? get() = (applicationContext as? HeldRunsOwner)?.heldRuns

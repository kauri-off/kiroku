package io.github.kauri_off.kiroku.notify

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import java.time.Instant

// The app's process with an engine that only tells which runs the device started.
class RecordedRunsApplication :
    Application(),
    HeldRunsOwner {
    val started = Channel<RunTrigger>(Channel.UNLIMITED)

    private val runs =
        object : NotificationRuns {
            override suspend fun run(trigger: RunTrigger): RunReport {
                started.send(trigger)
                return RunReport(trigger, Instant.now(), Instant.now(), RunEnd.Done)
            }
        }

    override val heldRuns = HeldRuns(CoroutineScope(Dispatchers.Default)) { runs }
}

// Starts the device tests in that application, so that the manifest's receiver reaches the recorded runs.
class RecordedRunsRunner : AndroidJUnitRunner() {
    override fun newApplication(
        loader: ClassLoader,
        name: String,
        context: Context,
    ): Application = super.newApplication(loader, RecordedRunsApplication::class.java.name, context)
}

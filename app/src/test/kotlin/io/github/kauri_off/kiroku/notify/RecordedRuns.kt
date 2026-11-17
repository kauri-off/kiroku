package io.github.kauri_off.kiroku.notify

import android.app.Application
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.notify.RunEnd
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// An engine that only tells which runs it was asked for; a run goes on to its end when its caller is cancelled, as the engine's does.
class RecordedRuns : NotificationRuns {
    val started = CopyOnWriteArrayList<RunTrigger>()
    val ended = CopyOnWriteArrayList<RunTrigger>()

    // What a run waits for before it ends; none lets it end at once.
    @Volatile
    var until: CompletableDeferred<Unit>? = null

    private val own = CoroutineScope(Dispatchers.Unconfined)

    override suspend fun run(trigger: RunTrigger): RunReport =
        own
            .async {
                started += trigger
                until?.await()
                ended += trigger
                RunReport(trigger, Instant.EPOCH, Instant.EPOCH, RunEnd.Done)
            }.await()
}

// An application whose runs are recorded, each started on the thread that asks for it.
class RecordedRunsApplication :
    Application(),
    HeldRunsOwner {
    val runs = RecordedRuns()
    override val heldRuns = HeldRuns(CoroutineScope(Dispatchers.Unconfined)) { runs }
}

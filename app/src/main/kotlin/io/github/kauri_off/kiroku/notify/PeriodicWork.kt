package io.github.kauri_off.kiroku.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.notify.RunReport
import io.github.kauri_off.kiroku.core.notify.RunTrigger
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.ListWork
import io.github.kauri_off.kiroku.wiring.WithoutScreen
import io.github.kauri_off.kiroku.wiring.withoutScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

// What a periodic run came to: the list's download and the airing's update (null when it outlasted its wait), the engine's run.
data class PeriodicReport(
    val list: ListWork<Outcome<Unit>>?,
    val airing: Outcome<Unit>?,
    val notifications: RunReport?,
)

// Keeps the app right while nobody opens it (N2, S7): the list and the airing data first, so the engine reads them as they now are.
suspend fun periodicRun(app: WithoutScreen): PeriodicReport {
    // Each wait is bounded so that the engine's run comes inside the system's ten minutes; what was asked goes on in the process.
    val list = attempted { withTimeoutOrNull(LIST_WAIT) { app.syncList() } }
    val airing = attempted { withTimeoutOrNull(AIRING_WAIT) { app.updateAiring() } }
    val notifications = app.notificationRuns?.run(RunTrigger.Periodic)
    return PeriodicReport(list.getOrThrow(), airing.getOrThrow(), notifications)
}

private val LIST_WAIT = 3.minutes
private val AIRING_WAIT = 5.minutes

// A step that throws does not keep the engine from its run; it fails the work after it.
private inline fun <T> attempted(step: () -> T): Result<T> =
    try {
        Result.success(step())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

// One worker for both works: with a network the whole run, without one the engine's alone, from what is stored (N2).
class PeriodicWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): ListenableWorker.Result {
        val app = applicationContext.withoutScreen ?: return ListenableWorker.Result.success()
        if (inputData.getBoolean(ONLINE, false)) periodicRun(app) else app.notificationRuns?.run(RunTrigger.Periodic)
        return ListenableWorker.Result.success()
    }

    companion object {
        internal val ONLINE = notShown("online")
    }
}

// Two works: the system lifts the standby bucket's network block only for a job that requires a network, which never runs offline.
object PeriodicWork {
    // Requires a connected network: renews, downloads, updates the airing data, then runs the engine.
    val ONLINE = notShown("periodic")

    // Requires nothing: the engine's run from what is stored.
    val STORED = notShown("periodic.stored")

    // How often each runs at most; the system runs them later in Doze and for an app the user rarely opens.
    val INTERVAL: Duration = Duration.ofHours(6)

    // Each once under its name: scheduled again, it keeps its next run and takes this build's request; the first waits an interval too.
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(ONLINE, ExistingPeriodicWorkPolicy.UPDATE, request(online = true))
        work.enqueueUniquePeriodicWork(STORED, ExistingPeriodicWorkPolicy.UPDATE, request(online = false))
    }

    private fun request(online: Boolean) =
        PeriodicWorkRequestBuilder<PeriodicWorker>(INTERVAL)
            .setInitialDelay(INTERVAL)
            .setInputData(workDataOf(PeriodicWorker.ONLINE to online))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(if (online) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build(),
            ).build()
}

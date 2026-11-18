package io.github.kauri_off.kiroku.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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

// What a periodic run came to: the list's download, the airing's update (null when it outlasted its wait), the engine's run.
data class PeriodicReport(
    val list: ListWork<Outcome<Unit>>,
    val airing: Outcome<Unit>?,
    val notifications: RunReport?,
)

// Keeps the app right while nobody opens it (N2, S7): the list and the airing data first, so the engine reads them as they now are.
suspend fun periodicRun(app: WithoutScreen): PeriodicReport {
    val list = attempted { app.syncList() }
    // The update goes on past the wait in the process; a whole system run is ten minutes, and the engine's comes after it.
    val airing = attempted { withTimeoutOrNull(AIRING_WAIT) { app.updateAiring() } }
    val notifications = app.notificationRuns?.run(RunTrigger.Periodic)
    return PeriodicReport(list.getOrThrow(), airing.getOrThrow(), notifications)
}

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

class PeriodicWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        applicationContext.withoutScreen?.let { periodicRun(it) }
        return Result.success()
    }
}

object PeriodicWork {
    val NAME = notShown("periodic")

    // How often it runs at most; the system runs it later in Doze and for an app the user rarely opens.
    val INTERVAL: Duration = Duration.ofHours(6)

    // Once under its name: scheduled again, it keeps its next run and takes this build's request; the first waits an interval too.
    fun schedule(context: Context) {
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PeriodicWorker>(INTERVAL).setInitialDelay(INTERVAL).build(),
            )
    }
}

package io.github.kauri_off.kiroku.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.DeviceClock
import io.github.kauri_off.kiroku.wiring.reportUnexpected
import io.github.kauri_off.kiroku.wiring.withoutScreen
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant

// A tap on "Mark watched", as its tries carry it: what it marks, when it was tapped, and how many tries came before.
data class Tap(
    val mark: MarkWatched,
    val tappedAt: Instant,
    val tried: Int = 0,
)

// Scheduled work keeps a tap across the death of its process: a try under way when the process dies is made again, and
// sends nothing twice, as the write is the same however often it is sent (N9).
object MarkWatchedWork {
    // Taps on one title's notifications are tried one after another, in the order they came; each title has its own end of the waiting.
    fun tapped(
        context: Context,
        tap: Tap,
    ): List<Operation> {
        val ends = tap.tappedAt + MarkWatchedTries.WAITED_AT_MOST
        val end = request<WaitingEndWorker>(tap).setInitialDelay(Duration.between(DeviceClock.instant(), ends).coerceAtLeast(Duration.ZERO))
        val waitingEnds = WorkManager.getInstance(context).enqueueUniqueWork(endName(tap.mark), ExistingWorkPolicy.REPLACE, end.build())
        return listOf(waitingEnds, next(context, tap, Duration.ZERO))
    }

    // A try at once runs as expedited work, which before Android 12 is a foreground service; a later one waits for its time.
    fun next(
        context: Context,
        tap: Tap,
        after: Duration,
    ): Operation =
        WorkManager.getInstance(context).enqueueUniqueWork(triesName(tap.mark), ExistingWorkPolicy.APPEND_OR_REPLACE, tryOf(tap, after))

    fun tryOf(
        tap: Tap,
        after: Duration,
    ): OneTimeWorkRequest =
        request<MarkWatchedWorker>(tap)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .apply {
                val later = after > Duration.ZERO
                if (later) setInitialDelay(after) else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()

    fun triesName(mark: MarkWatched): String = notShown("mark-watched:${mark.source}:${mark.title.value}")

    fun endName(mark: MarkWatched): String = notShown("mark-watched-ends:${mark.source}:${mark.title.value}")

    private inline fun <reified W : ListenableWorker> request(tap: Tap) =
        OneTimeWorkRequest.Builder(W::class.java).setInputData(tap.data()).addTag(TAG)

    private fun Tap.data(): Data =
        Data
            .Builder()
            .putString(SOURCE, mark.source)
            .putLong(TITLE, mark.title.value)
            .putInt(EPISODE, mark.episode)
            .putLong(TAPPED_AT, tappedAt.toEpochMilli())
            .putInt(TRIED, tried)
            .build()

    // Null for work this build cannot read.
    fun Data.tap(): Tap? {
        val source = getString(SOURCE) ?: return null
        val title = getLong(TITLE, 0).takeIf { it > 0 } ?: return null
        val episode = getInt(EPISODE, 0).takeIf { it > 0 } ?: return null
        val tappedAt = getLong(TAPPED_AT, -1).takeIf { it >= 0 } ?: return null
        return Tap(MarkWatched(source, AnimeId(title), episode), Instant.ofEpochMilli(tappedAt), getInt(TRIED, 0))
    }

    val TAG = notShown("mark-watched")
    private val SOURCE = notShown("source")
    private val TITLE = notShown("title")
    private val EPISODE = notShown("episode")
    private val TAPPED_AT = notShown("tappedAt")
    private val TRIED = notShown("tried")
}

// One try of a tap; another is scheduled when it has to wait.
class MarkWatchedWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val tap = with(MarkWatchedWork) { inputData.tap() } ?: return Result.success()
        val app = applicationContext.withoutScreen ?: return Result.success()
        val tried = MarkWatchedTries(app, DeviceClock, reportUnexpected).attempt(tap.mark, tap.tappedAt, tap.tried)
        if (tried is Tried.Again) MarkWatchedWork.next(applicationContext, tap.copy(tried = tap.tried + 1), tried.after).await()
        return Result.success()
    }

    // Before Android 12 the system runs expedited work as a foreground service, which shows this while it runs.
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(SAVING_ID, saving(applicationContext))

    companion object {
        // Neither an episode's notification nor the summary: those are 1 and 2 under their tags.
        const val SAVING_ID = 3
    }
}

// A day after a tap: tries still waiting, for a connection the device never got or for their time, end with nothing sent.
class WaitingEndWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val tap = with(MarkWatchedWork) { inputData.tap() } ?: return Result.success()
        val work = WorkManager.getInstance(applicationContext)
        val name = MarkWatchedWork.triesName(tap.mark)
        val waiting = work.getWorkInfosForUniqueWorkFlow(name).first().filterNot { it.state.isFinished }
        // A try under way ends the waiting itself: it sends nothing once the day is over, and says so.
        if (waiting.isEmpty() || waiting.any { it.state == WorkInfo.State.RUNNING }) return Result.success()
        work.cancelUniqueWork(name).await()
        val app = applicationContext.withoutScreen ?: return Result.success()
        MarkWatchedTries(app, DeviceClock, reportUnexpected).gaveUp(tap.mark)
        return Result.success()
    }
}

// N9: "Saving…" on its own low-importance channel, named in the language in effect.
fun saving(context: Context): Notification {
    context
        .getSystemService(NotificationManager::class.java)
        .createNotificationChannel(
            NotificationChannel(SAVING_CHANNEL, context.getString(R.string.notify_saving_changes), NotificationManager.IMPORTANCE_LOW),
        )
    return NotificationCompat
        .Builder(context, SAVING_CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.notify_saving))
        .setOngoing(true)
        .build()
}

val SAVING_CHANNEL = notShown("saving")

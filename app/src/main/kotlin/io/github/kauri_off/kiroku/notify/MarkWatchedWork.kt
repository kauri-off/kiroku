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
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.wiring.DeviceClock
import io.github.kauri_off.kiroku.wiring.reportUnexpected
import io.github.kauri_off.kiroku.wiring.withoutScreen
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Duration
import java.time.Instant

// A tap on "Mark watched", as its tries carry it: what it marks, when it was tapped, how many tries came before, and the
// sign-in whose list the first try found, which every later try must find again.
data class Tap(
    val mark: MarkWatched,
    val tappedAt: Instant,
    val tried: Int = 0,
    val madeIn: SignInId? = null,
)

// Scheduled work keeps a tap across the death of its process: a try under way when the process dies is made again, and
// sends nothing twice, as the write is the same however often it is sent (N9).
object MarkWatchedWork {
    // Taps on one title's notifications are tried one after another, in the order they came; each tap has its own end of the waiting.
    fun tapped(
        context: Context,
        tap: Tap,
    ): List<Operation> {
        val ends = tap.tappedAt + MarkWatchedTries.WAITED_AT_MOST
        val end =
            request<WaitingEndWorker>(
                tap,
            ).addTag(endTag(tap)).setInitialDelay(Duration.between(DeviceClock.instant(), ends).coerceAtLeast(Duration.ZERO))
        return listOf(WorkManager.getInstance(context).enqueue(end.build()), next(context, tap, Duration.ZERO))
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
            .addTag(tapTag(tap))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .apply {
                val later = after > Duration.ZERO
                if (later) setInitialDelay(after) else setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()

    // S9: a sign-out ends the source's taps, and nothing of them stays in scheduled work's records.
    suspend fun forget(
        context: Context,
        source: String,
    ) {
        // Scheduled work never set up on this device holds no tap, and is not set up for nothing.
        if (!WorkManager.isInitialized() && !File(context.noBackupFilesDir, WORK_DATABASE).exists()) return
        val work = WorkManager.getInstance(context)
        work.cancelAllWorkByTag(sourceTag(source)).await()
        work.pruneWork().await()
    }

    fun triesName(mark: MarkWatched): String = notShown("mark-watched:${mark.source}:${mark.title.value}")

    fun sourceTag(source: String): String = notShown("mark-watched:$source")

    // The tries of one tap, which its end of the waiting looks for.
    fun tapTag(tap: Tap): String = notShown("mark-watched:${tap.mark.source}:${tap.mark.title.value}:${tap.tappedAt.toEpochMilli()}")

    fun endTag(tap: Tap): String = notShown("mark-watched-end:${tap.mark.source}:${tap.mark.title.value}:${tap.tappedAt.toEpochMilli()}")

    private inline fun <reified W : ListenableWorker> request(tap: Tap) =
        OneTimeWorkRequest.Builder(W::class.java).setInputData(tap.data()).addTag(sourceTag(tap.mark.source))

    private fun Tap.data(): Data {
        val said = mark.said
        return Data
            .Builder()
            .putString(SOURCE, mark.source)
            .putLong(TITLE, mark.title.value)
            .putInt(EPISODE, mark.episode)
            .putLong(TAPPED_AT, tappedAt.toEpochMilli())
            .putInt(TRIED, tried)
            .apply { madeIn?.let { putString(MADE_IN, it.value) } }
            .putInt(SAID_EPISODE, said.episode)
            .apply { said.toWatch?.let { putInt(SAID_TO_WATCH, it) } }
            .putInt(SAID_MARKS, said.marks)
            .putLong(SAID_AIRED_AT, said.airedAt.toEpochMilli())
            .putLong(SAID_NOTIFY_AT, said.notifyAt.toEpochMilli())
            .build()
    }

    // Null for work this build cannot read.
    fun Data.tap(): Tap? {
        val source = getString(SOURCE) ?: return null
        val title = getLong(TITLE, 0).takeIf { it > 0 } ?: return null
        val episode = getInt(EPISODE, 0).takeIf { it > 0 } ?: return null
        val tappedAt = getLong(TAPPED_AT, -1).takeIf { it >= 0 } ?: return null
        val said =
            Shown(
                getInt(SAID_EPISODE, 0).takeIf { it > 0 } ?: return null,
                if (hasKeyWithValueOfType(SAID_TO_WATCH, Integer::class.java)) getInt(SAID_TO_WATCH, 0) else null,
                getInt(SAID_MARKS, 0).takeIf { it > 0 } ?: return null,
                Instant.ofEpochMilli(getLong(SAID_AIRED_AT, -1).takeIf { it >= 0 } ?: return null),
                Instant.ofEpochMilli(getLong(SAID_NOTIFY_AT, -1).takeIf { it >= 0 } ?: return null),
            )
        val mark = MarkWatched(source, AnimeId(title), episode, said)
        return Tap(mark, Instant.ofEpochMilli(tappedAt), getInt(TRIED, 0), getString(MADE_IN)?.let(::SignInId))
    }

    private val WORK_DATABASE = notShown("androidx.work.workdb")
    private val SOURCE = notShown("source")
    private val TITLE = notShown("title")
    private val EPISODE = notShown("episode")
    private val TAPPED_AT = notShown("tappedAt")
    private val TRIED = notShown("tried")
    private val MADE_IN = notShown("madeIn")
    private val SAID_EPISODE = notShown("said.episode")
    private val SAID_TO_WATCH = notShown("said.toWatch")
    private val SAID_MARKS = notShown("said.marks")
    private val SAID_AIRED_AT = notShown("said.airedAt")
    private val SAID_NOTIFY_AT = notShown("said.notifyAt")
}

// One try of a tap; another is scheduled when it has to wait.
class MarkWatchedWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val tap = with(MarkWatchedWork) { inputData.tap() } ?: return Result.success()
        val app = applicationContext.withoutScreen ?: return Result.success()
        val tried = MarkWatchedTries(app, DeviceClock, reportUnexpected).attempt(tap.mark, tap.tappedAt, tap.tried, tap.madeIn)
        if (tried is Tried.Again) {
            MarkWatchedWork.next(applicationContext, tap.copy(tried = tap.tried + 1, madeIn = tried.madeIn), tried.after).await()
        }
        return Result.success()
    }

    // Before Android 12 the system runs expedited work as a foreground service, which shows this while it runs.
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(SAVING_ID, saving(applicationContext))

    companion object {
        // Neither an episode's notification nor the summary: those are 1 and 2 under their tags.
        const val SAVING_ID = 3
    }
}

// A day after a tap: when its tries still wait, for a connection the device never got or for their time, the title's
// notification says it could not be saved. They are left to end themselves, sending nothing once the day is over, so
// that the taps after them on the title keep their turn and a try that has just started is not cut off.
class WaitingEndWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val tap = with(MarkWatchedWork) { inputData.tap() } ?: return Result.success()
        val tries = WorkManager.getInstance(applicationContext).getWorkInfosByTagFlow(MarkWatchedWork.tapTag(tap)).first()
        val waiting = tries.filterNot { it.state.isFinished }
        if (waiting.isEmpty() || waiting.any { it.state == WorkInfo.State.RUNNING }) return Result.success()
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

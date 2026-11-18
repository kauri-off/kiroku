package io.github.kauri_off.kiroku.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.notShown
import java.time.Instant

// "Mark watched" from a title's notification: whose list the notification is of, the title, the episode the action marks,
// and what the notification said, so that one saying it could not be saved is the engine's notification still.
data class MarkWatched(
    val source: String,
    val title: AnimeId,
    val episode: Int,
    val said: Shown,
)

// What a tap on a title's notification starts: the app's screen, which opens the title over the List (section 6.3).
fun tapIntent(
    context: Context,
    title: AnimeId,
): Intent =
    Intent(context, MainActivity::class.java)
        .setData(address(title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

// What a tap on the group's summary starts: the app's screen as it was.
fun openIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

// The action's broadcast, to this app's receiver alone, which no other app can send to.
fun markWatchedIntent(
    context: Context,
    mark: MarkWatched,
): Intent =
    Intent(context, MarkWatchedReceiver::class.java)
        .setAction(MARK_WATCHED)
        // The source is in the address too, so that two sources' actions for one title are two pending intents.
        .setData(
            address(mark.title)
                .buildUpon()
                .appendQueryParameter(SOURCE, mark.source)
                .build(),
        ).putExtra(EPISODE, mark.episode)
        .putExtras(mark.said.extras())

// The title a tap on its notification opens; null for any other start, and for one the system replays from the recent tasks.
fun Intent.tappedTitle(): AnimeId? = if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) null else data?.let(::titleOf)

fun Intent.markWatched(): MarkWatched? {
    if (action != MARK_WATCHED) return null
    val title = data?.let(::titleOf) ?: return null
    val source = data?.getQueryParameter(SOURCE)?.takeIf { it.isNotEmpty() } ?: return null
    val episode = getIntExtra(EPISODE, 0).takeIf { it > 0 } ?: return null
    val said = extras?.shown() ?: return null
    return MarkWatched(source, title, episode, said)
}

// What a notification was posted with, as its extras and an intent's carry it.
internal fun Shown.extras(): Bundle =
    Bundle().apply {
        putInt(SAID_EPISODE, episode)
        toWatch?.let { putInt(SAID_TO_WATCH, it) }
        putInt(SAID_MARKS, marks)
        putString(SAID_AIRED_AT, airedAt.toString())
        putString(SAID_NOTIFY_AT, notifyAt.toString())
    }

// Null for a bundle without them.
internal fun Bundle.shown(): Shown? {
    val toWatch = if (containsKey(SAID_TO_WATCH)) getInt(SAID_TO_WATCH) else null
    val airedAt = getString(SAID_AIRED_AT)?.let(Instant::parse) ?: return null
    val notifyAt = getString(SAID_NOTIFY_AT)?.let(Instant::parse) ?: return null
    if (!containsKey(SAID_EPISODE) || !containsKey(SAID_MARKS)) return null
    return Shown(getInt(SAID_EPISODE), toWatch, getInt(SAID_MARKS), airedAt, notifyAt)
}

// Both are immutable: no app that is handed one can change where it goes or what it carries.
internal fun Context.tap(title: AnimeId): PendingIntent =
    PendingIntent.getActivity(this, 0, tapIntent(this, title), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

internal fun Context.open(): PendingIntent =
    PendingIntent.getActivity(this, 0, openIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

internal fun Context.markWatched(mark: MarkWatched): PendingIntent =
    PendingIntent.getBroadcast(this, 0, markWatchedIntent(this, mark), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

// One address a title: a pending intent is told from another by it, never by its extras.
private fun address(title: AnimeId): Uri =
    Uri
        .Builder()
        .scheme(SCHEME)
        .authority(TITLE)
        .appendPath(title.value.toString())
        .build()

private fun titleOf(address: Uri): AnimeId? =
    if (address.scheme == SCHEME && address.authority == TITLE) {
        address.pathSegments
            .singleOrNull()
            ?.toLongOrNull()
            ?.let(::AnimeId)
    } else {
        null
    }

private val SCHEME = notShown("kiroku")
private val TITLE = notShown("title")
private val MARK_WATCHED = notShown("io.github.kauri_off.kiroku.notify.MARK_WATCHED")
private val EPISODE = notShown("episode")
private val SOURCE = notShown("source")
private val SAID_EPISODE = notShown("kiroku.episode")
private val SAID_TO_WATCH = notShown("kiroku.toWatch")
private val SAID_MARKS = notShown("kiroku.marks")
private val SAID_AIRED_AT = notShown("kiroku.airedAt")
private val SAID_NOTIFY_AT = notShown("kiroku.notifyAt")

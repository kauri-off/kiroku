package io.github.kauri_off.kiroku.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.notShown

// "Mark watched" from a title's notification: the title and the episode the action marks.
data class MarkWatched(
    val title: AnimeId,
    val episode: Int,
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
        .setData(address(mark.title))
        .putExtra(EPISODE, mark.episode)

// The title a tap on its notification opens; null for any other start, and for one the system replays from the recent tasks.
fun Intent.tappedTitle(): AnimeId? = if (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) null else data?.let(::titleOf)

fun Intent.markWatched(): MarkWatched? {
    if (action != MARK_WATCHED) return null
    val title = data?.let(::titleOf) ?: return null
    val episode = getIntExtra(EPISODE, 0).takeIf { it > 0 } ?: return null
    return MarkWatched(title, episode)
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

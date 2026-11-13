package io.github.kauri_off.kiroku.shell

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// A snackbar a screen posts and the shell shows, so it outlives the screen and its view model (ARCHITECTURE 6.2).
data class ShellMessage(
    val text: Text,
    val action: Action? = null,
) {
    sealed interface Text {
        data class EpisodeWatched(
            val episode: Int,
        ) : Text

        data object MovedToCompleted : Text

        data object AddedToPlanned : Text

        data class RefreshFailed(
            val error: DataError,
        ) : Text

        data object CouldNotSave : Text
    }

    sealed interface Action {
        data class Undo(
            val write: ListWrite,
        ) : Action

        data class Retry(
            val write: ListWrite,
        ) : Action

        // Opens the title's edit sheet on the screen on top.
        data class Change(
            val id: AnimeId,
        ) : Action
    }

    // The only way back from what happened: the Undo of a title that left its tab, or the Retry of a failed save.
    val mustStay: Boolean get() = text == Text.MovedToCompleted && action is Action.Undo || action is Action.Retry

    // The title an Undo or a Retry writes.
    val title: AnimeId?
        get() =
            when (action) {
                is Action.Undo -> action.write.anime.id
                is Action.Retry -> action.write.anime.id
                else -> null
            }
}

// One message at a time; one that must stay is never replaced, and an ordinary one posted meanwhile is dropped.
class ShellMessages(
    private val scope: CoroutineScope,
    private val list: ListRepository,
) {
    // [serial] tells two equal messages in a row apart.
    data class Posted(
        val message: ShellMessage,
        val serial: Long,
    )

    private val shown = MutableStateFlow<Posted?>(null)
    val current: StateFlow<Posted?> = shown.asStateFlow()

    private val requested = MutableStateFlow<AnimeId?>(null)

    // A title whose edit sheet "Change" asked for, until a screen with an edit sheet takes it.
    val editRequest: StateFlow<AnimeId?> = requested.asStateFlow()

    private class Waiting(
        val posted: Posted,
        val watch: Job?,
    )

    // The values an Undo or a Retry would write over.
    private class Start(
        val values: ListValues?,
    )

    private val waiting = mutableListOf<Waiting>()

    private var serial = 0L

    fun post(message: ShellMessage) {
        val posted = Posted(message, ++serial)
        val showing = shown.value
        when {
            showing == null || !showing.message.mustStay -> shown.value = posted
            message.mustStay -> waiting += Waiting(posted, watch(posted))
        }
    }

    // A screen's write runs in the shell's scope, so the write and the message about it outlive the screen.
    fun write(
        write: ListWrite,
        message: (ListChange) -> ShellMessage? = { null },
    ) {
        forget(write.anime.id)
        scope.launch { list.write(write)?.let(message)?.let(::post) }
    }

    // A waiting Undo or Retry of a title the shell writes would act on values it did not see.
    private fun forget(id: AnimeId) {
        val stale = waiting.filter { it.posted.message.title == id }
        stale.forEach { it.watch?.cancel() }
        waiting -= stale.toSet()
    }

    // A message whose action could overwrite a newer edit still tells what happened, as one without Retry does.
    private fun withoutAction(posted: Posted) {
        val at = waiting.indexOfFirst { it.posted == posted }
        if (at >= 0) waiting[at] = Waiting(posted.copy(message = posted.message.copy(action = null)), watch = null)
    }

    // A waiting Undo or Retry is dropped once its title's values differ from those it would write over.
    private fun watch(posted: Posted): Job? {
        val id = posted.message.title ?: return null
        val undone = ((posted.message.action as? ShellMessage.Action.Undo)?.write as? ListWrite.Undo)?.change
        return scope.launch {
            var start = undone?.let { Start(it.after?.values) }
            var failures = 0
            while (true) {
                try {
                    list.observeEntry(id).map { it?.values }.first { values ->
                        val from = start ?: Start(values).also { start = it }
                        values != from.values
                    }
                    break
                } catch (_: DataException) {
                    // A Retry's failure has no values: a read after the store returns may hold a newer edit.
                    if (start == null) return@launch withoutAction(posted)
                    delay(minOf(REREAD * 2.0.pow(failures++), REREAD_LONGEST))
                }
            }
            waiting.removeAll { it.posted == posted }
        }
    }

    // The snackbar ended by itself, by a swipe or by its action; not when a newer message or a recreation cut it short.
    fun ended(
        posted: Posted,
        actionPerformed: Boolean,
    ) {
        if (actionPerformed) {
            when (val action = posted.message.action) {
                is ShellMessage.Action.Undo -> write(action.write)
                is ShellMessage.Action.Retry -> write(action.write)
                is ShellMessage.Action.Change -> requested.value = action.id
                null -> Unit
            }
        }
        if (shown.value == posted) {
            val next = waiting.removeFirstOrNull()
            next?.watch?.cancel()
            shown.value = next?.posted
        }
    }

    fun editTaken(id: AnimeId) {
        requested.compareAndSet(id, null)
    }

    internal companion object {
        // Past the engine's own read attempts and within a Long snackbar, so a waiting message is read before its turn.
        val REREAD = 5.seconds

        // Each failed read is logged, so a store that stays broken is read this seldom at most.
        val REREAD_LONGEST = 5.minutes
    }
}

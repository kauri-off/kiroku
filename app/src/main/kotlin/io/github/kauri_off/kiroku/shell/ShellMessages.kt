package io.github.kauri_off.kiroku.shell

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.sameAsWritten
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.WriteFailure
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
    val mangaOwner: SignInId? = null,
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

        data object MangaUnconfirmed : Text
    }

    sealed interface Action {
        data class Undo(
            val write: ListWrite,
        ) : Action

        data class Retry(
            val write: ListWrite.Retry,
        ) : Action

        data class MangaRecovery(
            val readOnly: Boolean,
            val run: () -> Unit,
        ) : Action

        // Opens the title's edit sheet on the screen on top.
        data class Change(
            val id: AnimeId,
            val owner: SignInId? = null,
        ) : Action
    }

    // The only way back from what happened: the Undo of a title that left its tab, or the Retry of a failed save.
    val mustStay: Boolean get() =
        text == Text.MovedToCompleted &&
            action is Action.Undo ||
            action is Action.Retry ||
            action is Action.MangaRecovery

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

    private val requested = MutableStateFlow<ShellMessage.Action.Change?>(null)

    // A title whose edit sheet "Change" asked for, until a screen with an edit sheet takes it.
    val editRequest: StateFlow<ShellMessage.Action.Change?> = requested.asStateFlow()

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

    // Ends the shown failure once its Retry would change nothing: MAL made it, or each value it sets changed since.
    private var made: Job? = null

    fun post(message: ShellMessage) {
        val posted = Posted(message, ++serial)
        val showing = shown.value
        when {
            showing == null || !showing.message.mustStay -> show(posted)
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

    // A waiting Undo of a title the shell writes would act on values it did not see; a Retry judges what is left itself.
    private fun forget(id: AnimeId) {
        val stale = waiting.filter { it.posted.message.title == id && it.posted.message.action !is ShellMessage.Action.Retry }
        stale.forEach { it.watch?.cancel() }
        waiting -= stale.toSet()
    }

    // A waiting Undo is dropped once its title's values differ from those it would write over, a Retry as a shown one is.
    private fun watch(posted: Posted): Job? {
        val id = posted.message.title ?: return null
        val action = posted.message.action
        val failure = (action as? ShellMessage.Action.Retry)?.write?.failure
        if (failure != null && !failure.judged) return null
        val undone = ((action as? ShellMessage.Action.Undo)?.write as? ListWrite.Undo)?.change
        return scope.launch {
            var start = undone?.let { Start(it.after?.values) }
            var failures = 0
            while (true) {
                try {
                    list.observeEntry(id).map { it?.values }.first { values ->
                        val from = start ?: Start(values).also { start = it }
                        failure?.ends(from, values) ?: !values.sameAsWritten(from.values)
                    }
                    break
                } catch (_: DataException) {
                    delay(minOf(REREAD * 2.0.pow(failures++), REREAD_LONGEST))
                }
            }
            waiting.removeAll { it.posted == posted }
        }
    }

    // A failed save or removal can be judged against the title; a step or a typed count applies to whatever it holds.
    private val WriteFailure.judged: Boolean get() = write is ListWrite.Save || write is ListWrite.Remove

    // W2: nothing is left to set, or the title changed to what it sets; the values first seen may be the rollback's.
    private fun WriteFailure.ends(
        start: Start,
        now: ListValues?,
    ): Boolean = left(now) == null || !now.sameAsWritten(start.values) && settledBy(now)

    // The snackbar ended by itself, by a swipe or by its action; not when a newer message or a recreation cut it short.
    fun ended(
        posted: Posted,
        actionPerformed: Boolean,
    ) {
        if (actionPerformed) {
            when (val action = posted.message.action) {
                is ShellMessage.Action.Undo -> write(action.write)
                is ShellMessage.Action.Retry -> write(action.write)
                is ShellMessage.Action.Change -> requested.value = action
                is ShellMessage.Action.MangaRecovery -> action.run()
                null -> Unit
            }
        }
        if (shown.value == posted) showNext()
    }

    internal fun remove(action: ShellMessage.Action.MangaRecovery) {
        waiting.removeAll { it.posted.message.action == action }
        if (shown.value?.message?.action == action) showNext()
    }

    internal fun retireManga(owner: SignInId) {
        waiting.removeAll { it.posted.message.mangaOwner == owner }
        if (shown.value?.message?.mangaOwner == owner) showNext()
    }

    private fun showNext() {
        val next = waiting.removeFirstOrNull()
        next?.watch?.cancel()
        show(next?.posted)
    }

    private fun show(posted: Posted?) {
        made?.cancel()
        shown.value = posted
        made = posted?.let(::endWhenMade)
    }

    private fun endWhenMade(posted: Posted): Job? {
        val retry = (posted.message.action as? ShellMessage.Action.Retry)?.write ?: return null
        val failure = retry.failure
        if (!failure.judged) return null
        return scope.launch {
            var start: Start? = null
            try {
                list.observeEntry(retry.anime.id).map { it?.values }.first { now ->
                    failure.ends(start ?: Start(now).also { start = it }, now)
                }
            } catch (_: DataException) {
                return@launch
            }
            if (shown.value == posted) showNext()
        }
    }

    fun editTaken(request: ShellMessage.Action.Change) {
        requested.compareAndSet(request, null)
    }

    // No message shown, waiting or asking for an edit sheet, as at a start.
    fun clear() {
        waiting.forEach { it.watch?.cancel() }
        waiting.clear()
        requested.value = null
        show(null)
    }

    internal companion object {
        // Past the engine's own read attempts and within a Long snackbar, so a waiting message is read before its turn.
        val REREAD = 5.seconds

        // Each failed read is logged, so a store that stays broken is read this seldom at most.
        val REREAD_LONGEST = 5.minutes
    }
}

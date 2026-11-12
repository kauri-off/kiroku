package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.repository.ListChange
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
        scope.launch { list.write(write)?.let(message)?.let(::post) }
    }

    // A waiting Undo or Retry is dropped once its title is written, so it never acts on values it did not see.
    private fun watch(posted: Posted): Job? {
        val id = posted.message.title ?: return null
        return scope.launch {
            try {
                list
                    .observeEntry(id)
                    .distinctUntilChanged()
                    .drop(1)
                    .first()
            } catch (_: DataException) {
                return@launch
            }
            waiting.removeAll { it.posted == posted }
        }
    }

    // The snackbar ended by itself, by a swipe or by its action; not when a newer message or a recreation cut it short.
    fun ended(
        posted: Posted,
        actionPerformed: Boolean,
    ) {
        if (shown.value == posted) {
            val next = waiting.removeFirstOrNull()
            next?.watch?.cancel()
            shown.value = next?.posted
        }
        if (!actionPerformed) return
        when (val action = posted.message.action) {
            is ShellMessage.Action.Undo -> write(action.write)
            is ShellMessage.Action.Retry -> write(action.write)
            is ShellMessage.Action.Change -> requested.value = action.id
            null -> Unit
        }
    }

    fun editTaken(id: AnimeId) {
        requested.compareAndSet(id, null)
    }
}

// Shows the current message on [host]; a message cut short by recreation is shown again afterwards.
@Composable
fun ShownMessages(
    messages: ShellMessages,
    host: SnackbarHostState,
) {
    val posted = messages.current.collectAsScreenState().value
    val text = posted?.message?.text?.let { text(it) }
    val action = posted?.message?.action?.let { actionLabel(it) }
    LaunchedEffect(posted) {
        if (posted == null || text == null) return@LaunchedEffect
        val result = host.show(text, action)
        messages.ended(posted, result == SnackbarResult.ActionPerformed)
    }
}

suspend fun SnackbarHostState.show(
    text: String,
    action: String?,
): SnackbarResult =
    showSnackbar(
        text,
        actionLabel = action,
        duration =
            if (action ==
                null
            ) {
                SnackbarDuration.Short
            } else {
                SnackbarDuration.Long
            },
    )

@Composable
fun text(text: ShellMessage.Text): String =
    when (text) {
        is ShellMessage.Text.EpisodeWatched -> stringResource(R.string.list_episode_watched, text.episode)
        ShellMessage.Text.MovedToCompleted -> stringResource(R.string.common_moved_to_completed)
        ShellMessage.Text.AddedToPlanned -> stringResource(R.string.title_added_to_planned)
        is ShellMessage.Text.RefreshFailed ->
            stringResource(
                when (text.error) {
                    DataError.NoConnection -> R.string.common_no_connection
                    DataError.Refused -> R.string.common_refused
                    else -> R.string.common_could_not_refresh
                },
            )
        ShellMessage.Text.CouldNotSave -> stringResource(R.string.shell_could_not_save)
    }

@Composable
fun actionLabel(action: ShellMessage.Action): String =
    stringResource(
        when (action) {
            is ShellMessage.Action.Undo -> R.string.common_undo
            is ShellMessage.Action.Retry -> R.string.design_retry
            is ShellMessage.Action.Change -> R.string.title_change
        },
    )

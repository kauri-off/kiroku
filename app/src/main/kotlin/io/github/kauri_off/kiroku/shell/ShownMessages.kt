package io.github.kauri_off.kiroku.shell

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.design.component.MessageCovers
import io.github.kauri_off.kiroku.design.component.TimedMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest

// A message that must stay runs only while it can be seen and tapped (section 7.2), and starts again in full after.
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ShownMessages(
    messages: ShellMessages,
    host: SnackbarHostState,
    covers: MessageCovers = remember { MessageCovers() },
) {
    val posted = messages.current.collectAsScreenState().value
    val text = posted?.message?.text?.let { text(it) }
    val action = posted?.message?.action?.let { actionLabel(it) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val window = LocalWindowInfo.current
    LaunchedEffect(posted, lifecycle, window, covers) {
        if (posted == null || text == null) return@LaunchedEffect
        val result =
            if (posted.message.mustStay) {
                combine(lifecycle.currentStateFlow, snapshotFlow { window.isWindowFocused && !covers.covered }) { state, seen ->
                    seen && state.isAtLeast(Lifecycle.State.STARTED)
                }.distinctUntilChanged()
                    .mapLatest { seen -> if (seen) host.show(text, action) else null }
                    .filterNotNull()
                    .first()
            } else {
                host.show(text, action)
            }
        messages.ended(posted, result == SnackbarResult.ActionPerformed)
    }
}

suspend fun SnackbarHostState.show(
    text: String,
    action: String?,
): SnackbarResult = showSnackbar(TimedMessage(text, action, if (action == null) SnackbarDuration.Short else SnackbarDuration.Long))

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

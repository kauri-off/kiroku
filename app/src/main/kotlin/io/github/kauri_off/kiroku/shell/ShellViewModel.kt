package io.github.kauri_off.kiroku.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.MangaListEditor
import io.github.kauri_off.kiroku.manga.MangaEditing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

// The one view model that works from its creation: it takes the write failures (W2) no shell was there to show.
class ShellViewModel(
    list: ListRepository,
    mangaEditor: MangaListEditor? = null,
    sessions: Flow<Session> = emptyFlow(),
) : ViewModel() {
    val messages = ShellMessages(viewModelScope, list)
    internal val mangaEditing = mangaEditor?.let { MangaEditing(viewModelScope, it, sessions, messages) }

    init {
        viewModelScope.launch {
            list.observeWriteFailures().collect { failure ->
                val refused = failure.error == DataError.SessionExpired || failure.error == DataError.ListEnded
                val retry = ShellMessage.Action.Retry(failure.retry).takeUnless { refused }
                messages.post(ShellMessage(ShellMessage.Text.CouldNotSave, retry))
            }
        }
    }
}

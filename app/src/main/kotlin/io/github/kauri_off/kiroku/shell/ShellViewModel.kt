package io.github.kauri_off.kiroku.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.repository.ListRepository
import kotlinx.coroutines.launch

// The one view model that works from its creation: write failures (W2) are dropped with no collector. A write that
// failed for the session offers no Retry, which could not succeed; the session banner offers sign-in.
class ShellViewModel(
    list: ListRepository,
) : ViewModel() {
    val messages = ShellMessages(viewModelScope, list)

    init {
        viewModelScope.launch {
            list.observeWriteFailures().collect { failure ->
                val retry = ShellMessage.Action.Retry(failure.retry).takeUnless { failure.error == DataError.SessionExpired }
                messages.post(ShellMessage(ShellMessage.Text.CouldNotSave, retry))
            }
        }
    }
}

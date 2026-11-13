package io.github.kauri_off.kiroku.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.repository.ListRepository
import kotlinx.coroutines.launch

// The one view model that works from its creation: write failures (W2) are dropped with no collector.
class ShellViewModel(
    list: ListRepository,
) : ViewModel() {
    val messages = ShellMessages(viewModelScope, list)

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

package io.github.kauri_off.kiroku.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                messages.post(ShellMessage(ShellMessage.Text.CouldNotSave, ShellMessage.Action.Retry(failure.retry)))
            }
        }
    }
}

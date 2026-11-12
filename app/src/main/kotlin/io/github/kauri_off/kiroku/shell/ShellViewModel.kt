package io.github.kauri_off.kiroku.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.repository.ListRepository
import kotlinx.coroutines.launch

// Lives with the activity's view models, so a message and a write failure (W2) survive recreation. Write failures are
// dropped with no collector, so this one listens for its whole life, whatever is shown.
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

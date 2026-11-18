package io.github.kauri_off.kiroku.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// The other users opened, newest first, each user once: only names that turned out to be users. They are kept with
// the person's choices, so they outlive the process and a sign-out forgets them.
class OpenedProfiles(
    private val preferences: PreferencesRepository,
) : ViewModel() {
    // Read while a Profile shows them.
    val names: StateFlow<List<String>> = preferences.observeOpenedProfiles().stateIn(viewModelScope, WhileShown, emptyList())

    // [sameUser] is the source's answer to which names are one user.
    fun opened(
        name: String,
        sameUser: (String, String) -> Boolean,
    ) {
        viewModelScope.launch { preferences.updateOpenedProfiles { it.withOpened(name, sameUser) } }
    }
}

// [name] first, with every other name of the same user taken out, at most RECENT_PROFILES.
internal fun List<String>.withOpened(
    name: String,
    sameUser: (String, String) -> Boolean,
): List<String> = if (firstOrNull() == name) this else (listOf(name) + filterNot { sameUser(it, name) }).take(RECENT_PROFILES)

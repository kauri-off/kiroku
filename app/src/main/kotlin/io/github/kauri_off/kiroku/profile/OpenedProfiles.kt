package io.github.kauri_off.kiroku.profile

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

// The other users opened in this process, newest first, each user once: only names that turned out to be users.
class OpenedProfiles : ViewModel() {
    private val held = mutableStateOf(emptyList<String>())

    val names: List<String> get() = held.value

    // [sameUser] is the source's answer to which names are one user.
    fun opened(
        name: String,
        sameUser: (String, String) -> Boolean,
    ) {
        val names = held.value
        if (names.firstOrNull() == name) return
        held.value = (listOf(name) + names.filterNot { sameUser(it, name) }).take(RECENT_PROFILES)
    }

    fun forget() {
        held.value = emptyList()
    }
}

package io.github.kauri_off.kiroku.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.kauri_off.kiroku.core.error.DataError

// The fixture user as the signed-in one; of other names, the fixtures' public lists and private one, and no one else.
internal val profileStandIn: ProfileStandIn? =
    object : ProfileStandIn {
        override val own: ProfileContent = ProfileFixtureStates.own

        override fun other(name: String): ProfileContent =
            when {
                ProfileFixtureStates.publicNames.any { it.equals(name, ignoreCase = true) } -> ProfileFixtureStates.other
                name.equals(ProfileFixtureStates.set.privateUserName, ignoreCase = true) -> ProfileContent.Failed(DataError.PrivateList)
                else -> ProfileContent.Failed(DataError.UnknownUser)
            }

        override var recent: List<String> by mutableStateOf(emptyList())
            private set

        override fun opened(name: String) {
            if (other(name) != ProfileContent.Failed(DataError.UnknownUser)) recent = recent.withOpened(name)
        }
    }

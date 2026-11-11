package io.github.kauri_off.kiroku.shell

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import io.github.kauri_off.kiroku.R
import kotlinx.serialization.Serializable

// The top-level destinations of 1.0, in the order of the bar and the rail.
enum class Destination(
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
    val root: NavKey,
) {
    List(R.string.shell_list, R.drawable.ic_list, R.drawable.ic_list, ListRoot),
    Search(R.string.shell_search, R.drawable.ic_search, R.drawable.ic_search, SearchRoot),
    Profile(R.string.shell_profile, R.drawable.ic_person_outline, R.drawable.ic_person, ProfileRoot),
}

@Serializable
data object ListRoot : NavKey

@Serializable
data object SearchRoot : NavKey

@Serializable
data object ProfileRoot : NavKey

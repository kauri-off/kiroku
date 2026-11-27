package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.link.isAcceptedUserName
import io.github.kauri_off.kiroku.core.link.readTypedLink
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.user.UserListProgress

data class ProfileState(
    val owner: ProfileOwner,
    val content: ProfileContent,
    val sessionExpired: Boolean = false,
    val refreshing: Boolean = false,
    // How far the read of another user's list is, the first one or one again; null while none is under way.
    val reading: UserListProgress? = null,
    // The "Open another profile" dialog; null while it is closed.
    val openProfile: OpenProfile? = null,
    val publicMangaAvailable: Boolean = false,
) {
    val canViewManga: Boolean
        get() = owner is ProfileOwner.Other && publicMangaAvailable && content != ProfileContent.Failed(DataError.UnknownUser)

    init {
        require(content !is ProfileContent.SignedOut || owner == ProfileOwner.Me)
        require(content !is ProfileContent.ListLoading || (owner is ProfileOwner.Other && reading != null))
        require(reading == null || owner is ProfileOwner.Other)
    }
}

sealed interface ProfileOwner {
    data object Me : ProfileOwner

    data class Other(
        val name: String,
    ) : ProfileOwner
}

sealed interface ProfileContent {
    data object Loading : ProfileContent

    data object SignedOut : ProfileContent

    // Another user's list, read part by part before their statistics can be computed; how far is the state's reading.
    data object ListLoading : ProfileContent

    data class Failed(
        val error: DataError,
    ) : ProfileContent

    data class Shown(
        val header: UserProfile?,
        val statistics: UserStatistics,
    ) : ProfileContent
}

// The sub-screens of the statistics, each opened by a row.
enum class ProfileStatistics { ScoreDistribution, Genres, FormatsAndYears }

data class OpenProfile(
    val name: String,
    val recent: List<String>,
) {
    init {
        require(recent.size <= RECENT_PROFILES)
    }
}

sealed interface ProfileEvent {
    data object Back : ProfileEvent

    data object OpenSettings : ProfileEvent

    data object SignIn : ProfileEvent

    data object Retry : ProfileEvent

    data object Refresh : ProfileEvent

    data class OpenStatistics(
        val screen: ProfileStatistics,
    ) : ProfileEvent

    data object ViewList : ProfileEvent

    data object ViewMangaList : ProfileEvent

    // Opens the dialog with [name] in its field.
    data class StartOpenProfile(
        val name: String = "",
    ) : ProfileEvent

    data class TypeName(
        val text: String,
    ) : ProfileEvent

    data class OpenProfile(
        val name: String,
    ) : ProfileEvent

    data object DismissOpenProfile : ProfileEvent
}

const val RECENT_PROFILES = 5

// What the dialog opens: the name typed or taken from a pasted profile link; null when there is none.
fun OpenProfile.nameToOpen(): String? = (profileInput(name) as? ProfileInput.Name)?.name

// The text is neither a name the app takes nor a profile link with one, so nothing opens.
val OpenProfile.refused: Boolean get() = profileInput(name) == ProfileInput.Refused

sealed interface ProfileInput {
    data object Blank : ProfileInput

    data class Name(
        val name: String,
    ) : ProfileInput

    data object Refused : ProfileInput
}

// The user a pasted profile link names, read as an opened link is, or the name typed; both by the app's name rule.
fun profileInput(text: String): ProfileInput {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ProfileInput.Blank
    (readTypedLink(trimmed) as? Link.Profile)?.let { return ProfileInput.Name(it.name) }
    return if (isAcceptedUserName(trimmed)) ProfileInput.Name(trimmed) else ProfileInput.Refused
}

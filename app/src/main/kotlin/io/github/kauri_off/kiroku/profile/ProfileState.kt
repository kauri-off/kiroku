package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.link.isAcceptedUserName
import io.github.kauri_off.kiroku.core.link.readTypedLink
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.model.UserStatistics

data class ProfileState(
    val owner: ProfileOwner,
    val content: ProfileContent,
    val sessionExpired: Boolean = false,
    val refreshing: Boolean = false,
    // The "Open another profile" dialog; null while it is closed.
    val openProfile: OpenProfile? = null,
) {
    init {
        require(content !is ProfileContent.SignedOut || owner == ProfileOwner.Me)
        require(content !is ProfileContent.ListLoading || owner is ProfileOwner.Other)
        // MAL's API gives the header only for the signed-in user.
        require(content !is ProfileContent.Shown || (content.header != null) == (owner == ProfileOwner.Me))
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

    // Another user's list, read page by page before their statistics can be computed.
    data class ListLoading(
        val loaded: Int,
        val total: Int?,
    ) : ProfileContent {
        init {
            require(loaded >= 0 && (total == null || total >= 0))
        }
    }

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

// The names opened last, newest first, each once whatever its case, as MAL's names ignore case.
fun List<String>.withOpened(name: String): List<String> =
    (listOf(name) + filterNot { it.equals(name, ignoreCase = true) }).take(RECENT_PROFILES)

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

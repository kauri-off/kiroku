package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.model.UserStatistics
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.notShown

data class ProfileState(
    val owner: ProfileOwner,
    val content: ProfileContent,
    val sessionExpired: Boolean = false,
    val refreshing: Boolean = false,
    // How far the read of another user's list is, the first one or one again; null while none is under way.
    val reading: UserListProgress? = null,
    // The "Open another profile" dialog; null while it is closed.
    val openProfile: OpenProfile? = null,
) {
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

// The text is a link, but not to a MyAnimeList profile or list, so it is not taken as a name.
val OpenProfile.notAProfileLink: Boolean get() = profileInput(name) == ProfileInput.OtherLink

sealed interface ProfileInput {
    data object Blank : ProfileInput

    data class Name(
        val name: String,
    ) : ProfileInput

    data object OtherLink : ProfileInput
}

// The user name in a myanimelist.net profile or list link, with or without its scheme, "www.", a further path, a
// trailing slash or a query; any other text with a scheme, or a host and a path, is another link; the rest is a name.
fun profileInput(text: String): ProfileInput {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ProfileInput.Blank
    profileLink.matchEntire(trimmed)?.let { return ProfileInput.Name(it.groupValues[1]) }
    return if (anyLink.containsMatchIn(trimmed)) ProfileInput.OtherLink else ProfileInput.Name(trimmed)
}

private val profileLink =
    Regex(
        notShown("""(?:https?://)?(?:www\.)?myanimelist\.net/(?:profile|animelist)/([^/?#\s]+)(?:/[^?#\s]*)?(?:[?#]\S*)?"""),
        RegexOption.IGNORE_CASE,
    )

private val anyLink = Regex(notShown("""://|^[^\s/]+\.[^\s/]+/"""))

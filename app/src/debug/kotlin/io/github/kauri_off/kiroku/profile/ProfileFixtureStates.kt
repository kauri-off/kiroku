package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.fixtures.FixtureUser
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.core.user.UserListProgress

// The Profile's states over the fixture set, for previews, screenshots and tests.
object ProfileFixtureStates {
    val set = Fixtures.reference

    val own = ProfileContent.Shown(set.user, set.userStatistics)

    // The signed-in user with a list of several thousand titles: the numbers at their widest.
    val ownLarge = ProfileContent.Shown(set.user, set.largeStatistics)

    // The signed-in user's list with no title scored.
    val nothingScored =
        ProfileContent.Shown(set.user, statisticsOf(set.entries.map { it.copy(values = it.values.copy(score = null)) }))

    // Another user with a list of several thousand titles.
    val other: ProfileContent.Shown by lazy { ProfileContent.Shown(null, statisticsOf(set.otherUserList)) }

    // Another user's few titles.
    val otherFew = ProfileContent.Shown(null, statisticsOf(set.otherUserEntries))

    // A list with nothing on it: every count zero and no mean score.
    val nothing = statisticsOf(emptyList())

    val otherName: String get() = set.otherUserName
    val privateName: String get() = set.privateUserName

    val unknownName: String get() = FixtureUser.unknownName

    // Five names opened earlier, newest first.
    val recent: List<String> get() = FixtureUser.openedNames

    fun me(content: ProfileContent = own) = ProfileState(ProfileOwner.Me, content)

    fun of(
        name: String,
        content: ProfileContent,
    ) = ProfileState(ProfileOwner.Other(name), content)

    // Another user's list read so far, before it is whole.
    fun reading(
        name: String,
        loaded: Int,
        total: Int?,
    ) = ProfileState(ProfileOwner.Other(name), ProfileContent.ListLoading, reading = UserListProgress(loaded, total))
}

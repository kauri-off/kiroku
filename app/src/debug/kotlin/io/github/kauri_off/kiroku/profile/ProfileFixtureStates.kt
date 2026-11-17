package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.fixtures.FixtureUser
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.core.user.UserListProgress

// The Profile's states over the fixture set, for previews, screenshots and tests.
object ProfileFixtureStates {
    val set = Fixtures.reference

    val own = ProfileContent.Shown(set.user, set.userStatistics)

    // Another user with a list of several thousand titles.
    val other = ProfileContent.Shown(null, set.largeStatistics)

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

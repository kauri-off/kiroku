package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.fixtures.FixtureUser
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.StatusCounts
import io.github.kauri_off.kiroku.core.model.UserStatistics

// The Profile's states over the fixture set, for previews, screenshots and the stand-in of debug builds.
object ProfileFixtureStates {
    val set = Fixtures.reference

    val own = ProfileContent.Shown(set.user, set.userStatistics)

    // Another user with a list of several thousand titles.
    val other = ProfileContent.Shown(null, set.largeStatistics)

    // A list with nothing on it: every count zero and no mean score.
    val nothing =
        UserStatistics(
            statuses = StatusCounts(watching = 0, completed = 0, onHold = 0, dropped = 0, planned = 0),
            daysWatched = 0.0,
            meanScore = null,
            scores = emptyMap(),
            genres = emptyList(),
            mediaTypes = emptyList(),
            years = emptyMap(),
        )

    val otherName: String get() = set.otherUserName
    val privateName: String get() = set.privateUserName

    val unknownName: String get() = FixtureUser.unknownName

    // The users whose list is public: the large one under every name but the private one.
    val publicNames: List<String> get() = listOf(otherName) + FixtureUser.guestNames

    // Five names opened earlier, newest first.
    val recent: List<String> get() = FixtureUser.openedNames

    fun me(content: ProfileContent = own) = ProfileState(ProfileOwner.Me, content)

    fun of(
        name: String,
        content: ProfileContent,
    ) = ProfileState(ProfileOwner.Other(name), content)
}

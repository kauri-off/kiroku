package io.github.kauri_off.kiroku.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.profile.ProfileFixtureStates.me
import io.github.kauri_off.kiroku.profile.ProfileFixtureStates.of
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class ProfileScreenshots : ScreenshotRegistry() {
    private val fixtures = ProfileFixtureStates

    override val content = contentCase("own") { Screen(me()) }
    override val loading = shown { Screen(me(ProfileContent.Loading)) }
    override val empty = shown { Screen(me(ProfileContent.Shown(fixtures.set.user, fixtures.nothing))) }
    override val error = shown { Screen(me(ProfileContent.Failed(DataError.NoConnection))) }

    override val cases =
        listOf(
            case("signed-out") { Screen(me(ProfileContent.SignedOut)) },
            case("session-expired") { Screen(me().copy(sessionExpired = true)) },
            case("other") { Screen(of(fixtures.otherName, fixtures.other)) },
            case("other-nothing-on-list") { Screen(of(fixtures.otherName, ProfileContent.Shown(null, fixtures.nothing))) },
            case("other-list-loading") { Screen(of(fixtures.otherName, ProfileContent.ListLoading(3_000, 12_400))) },
            case("other-list-loading-no-total") { Screen(of(fixtures.otherName, ProfileContent.ListLoading(1_200, null))) },
            case("other-loading") { Screen(of(fixtures.otherName, ProfileContent.Loading)) },
            case("other-error") { Screen(of(fixtures.otherName, ProfileContent.Failed(DataError.Refused))) },
            case("private-list") { Screen(of(fixtures.privateName, ProfileContent.Failed(DataError.PrivateList))) },
            case("unknown-name") { Screen(of(fixtures.unknownName, ProfileContent.Failed(DataError.UnknownUser))) },
            case("open-profile") { Screen(me().copy(openProfile = OpenProfile("", emptyList()))) },
            case("open-profile-recent") { Screen(me().copy(openProfile = OpenProfile("", fixtures.recent))) },
            case("open-profile-typed") {
                Screen(me().copy(openProfile = OpenProfile(fixtures.otherName, fixtures.recent)))
            },
            case("unknown-name-open-profile") {
                Screen(
                    of(fixtures.unknownName, ProfileContent.Failed(DataError.UnknownUser))
                        .copy(openProfile = OpenProfile(fixtures.unknownName, fixtures.recent)),
                )
            },
        )
}

@Composable
private fun Screen(state: ProfileState) = ProfileScreen(state, onEvent = {}, snackbars = remember { SnackbarHostState() })

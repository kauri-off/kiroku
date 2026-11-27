package io.github.kauri_off.kiroku.profile

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.user.UserListProgress
import io.github.kauri_off.kiroku.profile.ProfileFixtureStates.me
import io.github.kauri_off.kiroku.profile.ProfileFixtureStates.of
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.WindowSize
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase
import io.github.kauri_off.kiroku.screenshots.shown

class ProfileScreenshots : ScreenshotRegistry() {
    private val fixtures = ProfileFixtureStates

    override val content = contentCase("own") { Screen(me()) }
    override val loading = shown { Screen(me(ProfileContent.Loading)) }
    override val empty = shown { Screen(me(ProfileContent.Shown(fixtures.set.user, fixtures.nothing))) }
    override val error = shown { Screen(me(ProfileContent.Failed(DataError.StorageError))) }

    override val cases =
        listOf(
            case("signed-out") { Screen(me(ProfileContent.SignedOut)) },
            case("session-expired") { Screen(me().copy(sessionExpired = true)) },
            case("own-large") { Screen(me(fixtures.ownLarge)) },
            case("own-nothing-scored") { Screen(me(fixtures.nothingScored)) },
            case("own-no-header") { Screen(me(ProfileContent.Shown(null, fixtures.set.userStatistics))) },
            case("own-refreshing") { Screen(me().copy(refreshing = true)) },
            case("other", largeText = true) { Screen(of(fixtures.otherName, fixtures.other)) },
            case("other-with-manga", largeText = true) {
                Screen(of(fixtures.otherName, fixtures.other).copy(publicMangaAvailable = true))
            },
            case("private-anime-with-manga", largeText = true) {
                Screen(of(fixtures.privateName, ProfileContent.Failed(DataError.PrivateList)).copy(publicMangaAvailable = true))
            },
            case("offline-anime-with-manga", largeText = true) {
                Screen(of(fixtures.otherName, ProfileContent.Failed(DataError.NoConnection)).copy(publicMangaAvailable = true))
            },
            case("other-few") { Screen(of(fixtures.otherName, fixtures.otherFew)) },
            case("other-read-again") {
                Screen(
                    of(fixtures.otherName, fixtures.other).copy(refreshing = true, reading = UserListProgress(2_000, null)),
                )
            },
            case("other-nothing-on-list") { Screen(of(fixtures.otherName, ProfileContent.Shown(null, fixtures.nothing))) },
            case("other-list-loading") { Screen(fixtures.reading(fixtures.otherName, 3_000, 12_400)) },
            case("other-list-loading-no-total") { Screen(fixtures.reading(fixtures.otherName, 1_200, null)) },
            case("other-loading") { Screen(of(fixtures.otherName, ProfileContent.Loading)) },
            case("other-no-connection") { Screen(of(fixtures.otherName, ProfileContent.Failed(DataError.NoConnection))) },
            case("other-refused") { Screen(of(fixtures.otherName, ProfileContent.Failed(DataError.Refused))) },
            case("other-server-error") { Screen(of(fixtures.otherName, ProfileContent.Failed(DataError.ServerError))) },
            case("other-long-name", largeText = true) { Screen(of(LONG_NAME, fixtures.otherFew)) },
            case("private-list") { Screen(of(fixtures.privateName, ProfileContent.Failed(DataError.PrivateList))) },
            case("unknown-name") { Screen(of(fixtures.unknownName, ProfileContent.Failed(DataError.UnknownUser))) },
            case("open-profile") { Screen(me().copy(openProfile = OpenProfile("", emptyList()))) },
            case("open-profile-recent") { Screen(me().copy(openProfile = OpenProfile("", fixtures.recent))) },
            case("open-profile-other-link") {
                Screen(me().copy(openProfile = OpenProfile(OTHER_LINK, fixtures.recent)))
            },
            case("open-profile-landscape", window = WindowSize(widthDp = 891, heightDp = 411)) {
                Screen(me().copy(openProfile = OpenProfile(fixtures.otherName, fixtures.recent)))
            },
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

private const val OTHER_LINK = "https://anilist.co/user/sample_viewer/"

// As long as a MAL user name can be, in its widest letters.
private const val LONG_NAME = "WWWWWWWWWWWWWWWW"

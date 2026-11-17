package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.user.UserListProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStateTest {
    private val fixtures = ProfileFixtureStates

    // What a source says of a user besides the list may be nothing, for any user.
    @Test
    fun anyUserMayHaveAHeaderOrNone() {
        ProfileState(ProfileOwner.Other(fixtures.otherName), ProfileContent.Shown(fixtures.set.user, fixtures.nothing))
        ProfileState(ProfileOwner.Me, ProfileContent.Shown(null, fixtures.nothing))
    }

    @Test
    fun onlyTheOwnProfileIsSignedOutAndOnlyAnotherUsersListIsRead() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileState(ProfileOwner.Other(fixtures.otherName), ProfileContent.SignedOut)
        }
        val read = UserListProgress(1, 2)
        assertThrows(IllegalArgumentException::class.java) { ProfileState(ProfileOwner.Me, ProfileContent.ListLoading, reading = read) }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileState(ProfileOwner.Me, ProfileContent.Shown(null, fixtures.nothing), reading = read)
        }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { ProfileState(ProfileOwner.Other(fixtures.otherName), ProfileContent.ListLoading) }
        ProfileState(ProfileOwner.Other(fixtures.otherName), ProfileContent.Failed(DataError.PrivateList))
    }

    @Test
    fun theDialogListsAtMostFiveNames() {
        OpenProfile("", fixtures.recent)
        assertThrows(IllegalArgumentException::class.java) { OpenProfile("", fixtures.recent + "one_more") }
    }

    @Test
    fun theLastNameOpenedComesFirstAndTheOldestFallsOff() {
        val opened = OpenedProfiles()
        fixtures.recent.reversed().forEach { opened.opened(it, String::equals) }
        opened.opened("newest", String::equals)
        assertEquals(listOf("newest") + fixtures.recent.take(RECENT_PROFILES - 1), opened.names)
    }

    // Which names are one user is the source's answer, never the app's own comparison.
    @Test
    fun aNameOfAUserOpenedAgainMovesToTheFrontAsTheSourceTellsUsers() {
        val opened = OpenedProfiles()
        listOf("c_three", "b_two", "a_one").forEach { opened.opened(it, String::equals) }
        opened.opened("B_TWO") { a, b -> a.equals(b, ignoreCase = true) }
        assertEquals(listOf("B_TWO", "a_one", "c_three"), opened.names)
        opened.opened("A_ONE", String::equals)
        assertEquals(listOf("A_ONE", "B_TWO", "a_one", "c_three"), opened.names)
    }

    @Test
    fun theDialogOpensTheNameWithoutBlanksAndNothingForBlanks() {
        assertEquals("sample_viewer", OpenProfile("  sample_viewer ", emptyList()).nameToOpen())
        assertNull(OpenProfile("   ", emptyList()).nameToOpen())
    }

    @Test
    fun aPastedProfileOrListLinkGivesItsName() {
        val forms =
            listOf(
                "https://myanimelist.net/profile/sample_viewer",
                "http://myanimelist.net/profile/sample_viewer",
                "https://www.myanimelist.net/profile/sample_viewer/",
                "myanimelist.net/profile/sample_viewer",
                "www.myanimelist.net/profile/sample_viewer",
                "  https://myanimelist.net/profile/sample_viewer?view=animelist&status=1 ",
                "https://myanimelist.net/profile/sample_viewer/?view=1",
                "https://MyAnimeList.net/profile/sample_viewer/#top",
                "https://myanimelist.net/profile/sample_viewer\n",
                "https://myanimelist.net/profile/sample_viewer/reviews",
                "https://myanimelist.net/animelist/sample_viewer",
                "myanimelist.net/animelist/sample_viewer?status=2",
            )
        for (form in forms) assertEquals(form, ProfileInput.Name("sample_viewer"), profileInput(form))
    }

    @Test
    fun aPathSegmentIsTakenAsItIs() {
        for (name in listOf("a%20b", "имя", "Xi.nil")) {
            assertEquals(ProfileInput.Name(name), profileInput("https://myanimelist.net/profile/$name"))
        }
    }

    @Test
    fun anyOtherLinkIsNotAName() {
        val links =
            listOf(
                "m.myanimelist.net/profile/sample_viewer",
                "https://anilist.co/user/sample_viewer/",
                "anilist.co/user/sample_viewer",
                "evil.com/myanimelist.net/profile/sample_viewer",
                "myanimelist.net.evil.com/profile/sample_viewer",
                "https://example.com/profile/x",
                "myanimelist.net/anime/1",
                "myanimelist.net/profile/",
                "Check https://myanimelist.net/profile/sample_viewer",
            )
        for (link in links) assertEquals(link, ProfileInput.OtherLink, profileInput(link))
        assertNull(OpenProfile(links[1], emptyList()).nameToOpen())
        assertTrue(OpenProfile(links[1], emptyList()).notAProfileLink)
    }

    @Test
    fun anythingElseIsTakenAsAName() {
        assertEquals(ProfileInput.Name("sample_viewer"), profileInput(" sample_viewer "))
        assertEquals(ProfileInput.Name("some.name"), profileInput("some.name"))
        assertEquals(ProfileInput.Blank, profileInput("  "))
        assertFalse(OpenProfile("sample_viewer", emptyList()).notAProfileLink)
    }
}

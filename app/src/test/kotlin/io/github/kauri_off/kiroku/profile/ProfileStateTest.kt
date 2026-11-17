package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.Links
import io.github.kauri_off.kiroku.core.link.USER_NAME_MAX_LENGTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStateTest {
    private val fixtures = ProfileFixtureStates

    @Test
    fun anotherUserHasNoHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileState(ProfileOwner.Other(fixtures.otherName), ProfileContent.Shown(fixtures.set.user, fixtures.nothing))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileState(ProfileOwner.Me, ProfileContent.Shown(null, fixtures.nothing))
        }
    }

    @Test
    fun onlyTheOwnProfileIsSignedOutAndOnlyAnotherUsersListLoads() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileState(ProfileOwner.Other(fixtures.otherName), ProfileContent.SignedOut)
        }
        assertThrows(IllegalArgumentException::class.java) { ProfileState(ProfileOwner.Me, ProfileContent.ListLoading(1, 2)) }
        ProfileState(ProfileOwner.Other(fixtures.otherName), ProfileContent.Failed(DataError.PrivateList))
    }

    @Test
    fun theDialogListsAtMostFiveNames() {
        OpenProfile("", fixtures.recent)
        assertThrows(IllegalArgumentException::class.java) { OpenProfile("", fixtures.recent + "one_more") }
    }

    @Test
    fun theLastNameOpenedComesFirstAndTheOldestFallsOff() {
        val recent = fixtures.recent.withOpened("newest")
        assertEquals(listOf("newest") + fixtures.recent.take(RECENT_PROFILES - 1), recent)
    }

    @Test
    fun aNameOpenedAgainMovesToTheFrontWhateverItsCase() {
        val recent = listOf("a_one", "b_two", "c_three").withOpened("B_TWO")
        assertEquals(listOf("B_TWO", "a_one", "c_three"), recent)
    }

    @Test
    fun theDialogOpensTheNameWithoutBlanksAndNothingForBlanks() {
        assertEquals("sample_viewer", OpenProfile("  sample_viewer ", emptyList()).nameToOpen())
        assertNull(OpenProfile("   ", emptyList()).nameToOpen())
    }

    @Test
    fun aPastedProfileLinkGivesItsName() {
        val forms =
            listOf(
                "https://myanimelist.net/profile/sample_viewer",
                "https://www.myanimelist.net/profile/sample_viewer/",
                "myanimelist.net/profile/sample_viewer",
                "www.myanimelist.net/profile/sample_viewer",
                "  https://myanimelist.net/profile/sample_viewer?view=animelist&status=1 ",
                "https://myanimelist.net/profile/sample_viewer/?view=1",
                "https://MyAnimeList.net/profile/sample_viewer/#top",
                "https://myanimelist.net/profile/sample_viewer\n",
            )
        for (form in forms) assertEquals(form, ProfileInput.Name("sample_viewer"), profileInput(form))
        for ((address, link) in Links.profiles) assertEquals(address, ProfileInput.Name(link.name), profileInput(address))
    }

    @Test
    fun aPastedHttpProfileLinkGivesItsName() {
        assertEquals(ProfileInput.Name("sample_viewer"), profileInput("http://myanimelist.net/profile/sample_viewer"))
        assertEquals(ProfileInput.Name("sample_viewer"), profileInput("http://www.myanimelist.net/profile/sample%5Fviewer/"))
    }

    @Test
    fun aNameTypedOrInALinkIsTakenOnlyByTheAppsNameRule() {
        val refused =
            listOf(
                "a b",
                "some.name",
                "@me",
                "имя",
                "a".repeat(USER_NAME_MAX_LENGTH + 1),
                "myanimelist.net/profile/a%20b",
                "myanimelist.net/profile/%D0%B8%D0%BC%D1%8F",
                "myanimelist.net/profile/@me",
                "myanimelist.net/profile/%E2%80%8B",
            )
        for (text in refused) assertEquals(text, ProfileInput.Refused, profileInput(text))
        val taken = listOf("sample_viewer", "Quiet-Viewer9", "a".repeat(USER_NAME_MAX_LENGTH))
        for (name in taken) assertEquals(ProfileInput.Name(name), profileInput(" $name "))
    }

    @Test
    fun anyOtherLinkIsRefused() {
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
                "https://myanimelist.net/profile/sample_viewer/reviews",
                "https://myanimelist.net/animelist/sample_viewer",
                "myanimelist.net/profile/a%2Fb",
            ) + (Links.refused + Links.handedOn).filterNot { it.startsWith("http://") }
        for (link in links) assertEquals(link, ProfileInput.Refused, profileInput(link))
        assertNull(OpenProfile(links[1], emptyList()).nameToOpen())
        assertTrue(OpenProfile(links[1], emptyList()).refused)
    }

    @Test
    fun blankIsNeitherANameNorRefused() {
        assertEquals(ProfileInput.Blank, profileInput("  "))
        assertFalse(OpenProfile("  ", emptyList()).refused)
        assertFalse(OpenProfile("sample_viewer", emptyList()).refused)
    }

    @Test
    fun theStandInRemembersOnlyNamesThatExist() {
        val standIn = fixtureProfile
        val before = standIn.recent
        standIn.opened(fixtures.unknownName)
        assertEquals(before, standIn.recent)
        standIn.opened(fixtures.privateName)
        assertEquals(fixtures.privateName, standIn.recent.first())
        standIn.opened(fixtures.recent.last())
        assertEquals(fixtures.recent.last(), standIn.recent.first())
    }
}

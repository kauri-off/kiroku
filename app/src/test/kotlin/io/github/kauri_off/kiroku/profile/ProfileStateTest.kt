package io.github.kauri_off.kiroku.profile

import io.github.kauri_off.kiroku.core.error.DataError
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
    fun numbersShareTheRowEquallyWhenEveryLongestWordFits() {
        assertEquals(listOf(100, 100, 100), rowWidths(listOf(60, 90, 100), width = 320, gap = 10))
    }

    @Test
    fun aLongerWordTakesItsWidthAndTheOthersShareTheRest() {
        assertEquals(listOf(130, 85, 85), rowWidths(listOf(130, 40, 80), width = 320, gap = 10))
    }

    @Test
    fun numbersWhoseLongestWordsDoNotFitAreStacked() {
        assertNull(rowWidths(listOf(130, 100, 80), width = 320, gap = 10))
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

    @Test
    fun theStandInRemembersOnlyNamesThatExist() {
        val standIn = checkNotNull(profileStandIn)
        val before = standIn.recent
        standIn.opened(fixtures.unknownName)
        assertEquals(before, standIn.recent)
        standIn.opened(fixtures.privateName)
        assertEquals(fixtures.privateName, standIn.recent.first())
        standIn.opened(fixtures.recent.last())
        assertEquals(fixtures.recent.last(), standIn.recent.first())
    }
}

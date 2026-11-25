package io.github.kauri_off.kiroku.core.link

import io.github.kauri_off.kiroku.core.fixtures.Links
import io.github.kauri_off.kiroku.core.model.AnimeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LinkTest {
    @Test
    fun `ten forms of a title's link name its title`() {
        assertEquals(10, Links.titles.size)
        assertEquals(Links.titles, Links.titles.keys.associateWith(::readLink))
    }

    @Test
    fun `manga has its own link even when the numeric ID is also an anime ID`() {
        assertEquals(Links.manga, Links.manga.keys.associateWith(::readLink))
        assertNotEquals(readLink("https://myanimelist.net/anime/1"), readLink("https://myanimelist.net/manga/1"))
    }

    @Test
    fun `a profile's page on either host, with or without a slash, names its user decoded and otherwise as spelled`() {
        assertEquals(Links.profiles, Links.profiles.keys.associateWith(::readLink))
    }

    @Test
    fun `another address, a sub-page, an encoded slash, a name the app does not take and an id that is no positive number name nothing`() {
        val refused = Links.refused + Links.handedOn + Links.unreadable
        assertEquals(refused.associateWith { null }, refused.associateWith(::readLink))
    }

    @Test
    fun `a typed address may lack its scheme or have http, and is otherwise read as a link is`() {
        val typed =
            mapOf(
                "myanimelist.net/profile/sample_viewer" to Link.Profile("sample_viewer"),
                "www.myanimelist.net/profile/sample_viewer/" to Link.Profile("sample_viewer"),
                "MyAnimeList.net/profile/sample_viewer#top" to Link.Profile("sample_viewer"),
                "myanimelist.net/anime/1/Cowboy_Bebop" to Link.Title(AnimeId(1)),
                "http://myanimelist.net/profile/sample_viewer" to Link.Profile("sample_viewer"),
                "HTTP://www.myanimelist.net/anime/1" to Link.Title(AnimeId(1)),
            )
        assertEquals(typed, typed.keys.associateWith(::readTypedLink))
        assertEquals(Links.opened, Links.opened.keys.associateWith(::readTypedLink))
        val notLinks = listOf("myanimelist.net/profile/", "sample_viewer", "", "//myanimelist.net/profile/x")
        val refused = (Links.refused + Links.handedOn).filterNot { it.startsWith("http://") } + notLinks
        assertEquals(refused.associateWith { null }, refused.associateWith(::readTypedLink))
    }

    @Test
    fun `a user name the app takes is ASCII letters, digits, underscore and hyphen, one to the bound long`() {
        val taken = listOf("a", "Z", "0", "_", "-", "sample_viewer", "Quiet-Viewer9", "a".repeat(USER_NAME_MAX_LENGTH))
        val refused =
            listOf(
                "",
                " ",
                "a b",
                "a.b",
                "@me",
                "a/b",
                "a\u0000b",
                "a\nb",
                "\u202Ereganam",
                "\u200B",
                "имя",
                "é",
                "a".repeat(USER_NAME_MAX_LENGTH + 1),
                "a".repeat(200_000),
            )
        assertEquals(taken.associateWith { true }, taken.associateWith(::isAcceptedUserName))
        assertEquals(refused.associateWith { false }, refused.associateWith(::isAcceptedUserName))
    }
}

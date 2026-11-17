package io.github.kauri_off.kiroku.core.link

import io.github.kauri_off.kiroku.core.fixtures.Links
import io.github.kauri_off.kiroku.core.model.AnimeId
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkTest {
    @Test
    fun `ten forms of a title's link name its title`() {
        assertEquals(10, Links.titles.size)
        assertEquals(Links.titles, Links.titles.keys.associateWith(::readLink))
    }

    @Test
    fun `a profile's page on either host, with or without a slash, names its user decoded and otherwise as spelled`() {
        assertEquals(Links.profiles, Links.profiles.keys.associateWith(::readLink))
    }

    @Test
    fun `any other address, a sub-page, an encoded slash, an empty name and an id that is not a positive number name nothing`() {
        val refused = Links.refused + Links.handedOn + Links.unreadable
        assertEquals(refused.associateWith { null }, refused.associateWith(::readLink))
    }

    @Test
    fun `a typed address may lack its scheme and is otherwise read as a link is`() {
        val typed =
            mapOf(
                "myanimelist.net/profile/sample_viewer" to Link.Profile("sample_viewer"),
                "www.myanimelist.net/profile/sample_viewer/" to Link.Profile("sample_viewer"),
                "MyAnimeList.net/profile/sample_viewer#top" to Link.Profile("sample_viewer"),
                "myanimelist.net/anime/1/Cowboy_Bebop" to Link.Title(AnimeId(1)),
            )
        assertEquals(typed, typed.keys.associateWith(::readTypedLink))
        assertEquals(Links.opened, Links.opened.keys.associateWith(::readTypedLink))
        val notLinks = listOf("myanimelist.net/profile/", "sample_viewer", "", "//myanimelist.net/profile/x")
        val refused = Links.refused + Links.handedOn + notLinks
        assertEquals(refused.associateWith { null }, refused.associateWith(::readTypedLink))
    }
}

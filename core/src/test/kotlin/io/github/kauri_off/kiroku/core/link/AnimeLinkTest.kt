package io.github.kauri_off.kiroku.core.link

import io.github.kauri_off.kiroku.core.fixtures.AnimeLinks
import io.github.kauri_off.kiroku.core.model.AnimeId
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeLinkTest {
    @Test
    fun `ten forms of a title's link name its title`() {
        assertEquals(10, AnimeLinks.opened.size)
        assertEquals(AnimeLinks.opened.mapValues { AnimeId(it.value) }, AnimeLinks.opened.keys.associateWith(::animeLink))
    }

    @Test
    fun `any other address, a sub-page of a title and an id that is not a positive number name nothing`() {
        val refused = AnimeLinks.refused + AnimeLinks.refusedOnArrival + AnimeLinks.unreadable
        assertEquals(refused.associateWith { null }, refused.associateWith(::animeLink))
    }
}

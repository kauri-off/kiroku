package io.github.kauri_off.kiroku.core.model

import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WatchOrderTest {
    private fun title(
        id: Long,
        start: String?,
    ) = FixtureAnime.film.copy(
        id = AnimeId(id),
        titles = Titles("Title $id"),
        startDate = start?.let { PartialDate.Day(LocalDate.parse(it)) },
    )

    private val s1 = title(1, "2019-04-06")
    private val film = title(2, "2020-10-16")
    private val s2 = title(3, "2021-12-05")
    private val s3 = title(4, "2023-04-09")

    private fun sequel(anime: Anime) = RelatedAnime(anime, RelationType.Sequel)

    private fun prequel(anime: Anime) = RelatedAnime(anime, RelationType.Prequel)

    private fun pages(vararg pages: Pair<Anime, List<RelatedAnime>>) = pages.associate { (anime, related) -> anime.id to related }

    private fun order(
        title: Anime,
        pages: Map<AnimeId, List<RelatedAnime>>,
    ) = sequelChain(title, pages).order

    @Test
    fun `a film between two seasons comes between them, also when the first season names both as sequels`() {
        val pages =
            pages(
                s1 to listOf(sequel(s2), sequel(film)),
                film to listOf(prequel(s1), sequel(s2)),
                s2 to listOf(prequel(film), sequel(s3)),
                s3 to listOf(prequel(s2)),
            )
        for (start in listOf(s1, film, s2, s3)) {
            assertEquals(listOf(s1, film, s2, s3), order(start, pages))
        }
    }

    @Test
    fun `the relations decide the order before the dates do`() {
        val laterPrequel = title(5, "2024-01-01")
        val pages = pages(s1 to listOf(prequel(laterPrequel)), laterPrequel to listOf(sequel(s1)))
        assertEquals(listOf(laterPrequel, s1), order(s1, pages))
    }

    @Test
    fun `two sequels that do not name each other go by their start, an undated one last`() {
        val undated = title(6, null)
        val pages =
            pages(
                s1 to listOf(sequel(undated), sequel(s2), sequel(film)),
                film to listOf(prequel(s1)),
                s2 to listOf(prequel(s1)),
                undated to listOf(prequel(s1)),
            )
        assertEquals(listOf(s1, film, s2, undated), order(s1, pages))
    }

    @Test
    fun `a loop in the relations ends, with each title once, from its earliest title`() {
        val twoWay = pages(s1 to listOf(sequel(s2), prequel(s2)), s2 to listOf(sequel(s1), prequel(s1)))
        assertEquals(listOf(s1, s2), order(s2, twoWay))
        val round = pages(s1 to listOf(sequel(film)), film to listOf(sequel(s2)), s2 to listOf(sequel(s1)))
        assertEquals(listOf(s1, film, s2), order(film, round))
        val behindTheLoop = pages(s1 to listOf(sequel(film)), film to listOf(sequel(s2)), s2 to listOf(sequel(film), sequel(s3)))
        assertEquals(listOf(s1, film, s2, s3), order(s1, behindTheLoop))
    }

    @Test
    fun `a prequel reached from the middle comes first, and the walk reads the nearest titles first`() {
        val pages =
            pages(
                s3 to listOf(prequel(s2)),
                s2 to listOf(prequel(s1), sequel(s3)),
                s1 to listOf(sequel(s2)),
            )
        val chain = sequelChain(s3, pages)
        assertEquals(listOf(s1, s2, s3), chain.order)
        assertEquals(listOf(s3, s2, s1).map { it.id }, chain.walk)
    }

    @Test
    fun `only sequels and prequels are followed, not a relation the app does not know`() {
        val pages =
            pages(
                s1 to
                    listOf(
                        RelatedAnime(film, RelationType.Unrecognized("sequel_film")),
                        RelatedAnime(s2, RelationType.SideStory),
                        RelatedAnime(s3, RelationType.Other),
                    ),
            )
        val chain = sequelChain(s1, pages)
        assertEquals(listOf(s1), chain.order)
        assertEquals(listOf(s1.id), chain.walk)
        assertFalse(chain.shown)
    }

    @Test
    fun `a title with no sequel and no prequel has nothing to walk`() {
        val chain = sequelChain(s1, emptyMap())
        assertEquals(listOf(s1.id), chain.walk)
        assertFalse(chain.shown)
        assertFalse(sequelChain(s1, pages(s1 to emptyList())).shown)
    }

    @Test
    fun `a long chain holds the titles nearest to the title, a sequel before a prequel at the same distance`() {
        val line = (1L..12L).map { title(100 + it, "20%02d-01-01".format(it)) }
        val pages =
            line.withIndex().associate { (i, anime) ->
                anime.id to listOfNotNull(line.getOrNull(i - 1)?.let(::prequel), line.getOrNull(i + 1)?.let(::sequel))
            }
        val middle = sequelChain(line[5], pages)
        assertEquals(CHAIN_LIMIT, middle.order.size)
        assertEquals(listOf(5, 6, 4, 7, 3, 8).map { line[it].id }, middle.walk)
        assertEquals((3..8).map { line[it] }, middle.order)
        assertEquals(line.take(CHAIN_LIMIT), sequelChain(line[0], pages).order)
        assertEquals(line.takeLast(CHAIN_LIMIT), sequelChain(line.last(), pages).order)
    }

    @Test
    fun `the walk stops at the first page not read, and reading it keeps the titles walked before it`() {
        val s0 = title(7, "2018-01-01")
        val sMinus = title(8, "2017-01-01")
        val before =
            pages(
                s1 to listOf(sequel(s2), prequel(s0)),
                s0 to listOf(sequel(s1), prequel(sMinus)),
            )
        val partial = sequelChain(s1, before)
        assertEquals(listOf(s1, s2, s0).map { it.id }, partial.walk)
        assertEquals(listOf(s0, s1, s2), partial.order)
        val after = sequelChain(s1, before + pages(s2 to listOf(prequel(s1), sequel(s3))))
        assertTrue(after.walk.take(partial.walk.size) == partial.walk)
        assertEquals(listOf(s1, s2, s0, s3, sMinus).map { it.id }, after.walk)
    }
}

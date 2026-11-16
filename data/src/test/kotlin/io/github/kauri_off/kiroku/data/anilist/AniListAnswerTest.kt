package io.github.kauri_off.kiroku.data.anilist

import io.github.kauri_off.kiroku.data.anilist.AniListRecordings.json
import io.github.kauri_off.kiroku.data.anime.edited
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// The mapper on M3-01's recorded answers, one case each; a value is altered only where no recording has the case.
class AniListAnswerTest {
    private val reported = mutableListOf<AniListOddAnswer>()

    private fun read(body: String): AniListPage? = aniListPage(body, reported::add)

    private fun read(
        file: String,
        vararg path: String,
        value: JsonElement?,
    ): AniListPage? = read(json(file).edited(MEDIA + path.toList(), value).toString())

    private fun page(file: String): AniListPage = read(AniListRecordings.body(file))!!

    private fun nodes(file: String): List<Pair<Int, Instant>> =
        json(file)["data"]!!
            .jsonObject["Page"]!!
            .jsonObject["media"]!!
            .jsonArray
            .flatMap { it.jsonObject["airingSchedule"]!!.jsonObject["nodes"]!!.jsonArray }
            .map { it.jsonObject["episode"]!!.jsonPrimitive.int to Instant.ofEpochSecond(it.jsonObject["airingAt"]!!.jsonPrimitive.long) }

    @Test
    fun `a weekly title has every episode with its time, and AniList's total`() {
        val entry = page("airing_weekly").entries.single()

        assertEquals(61987, entry.malId)
        assertEquals(12, entry.total)
        assertEquals(nodes("airing_weekly"), entry.episodes.map { it.number to it.airsAt })
        assertEquals((1..12).toList(), entry.episodes.map { it.number })
        assertFalse(entry.moreEpisodes)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a releasing title without episode times is an entry with no episodes`() {
        val entry = page("releasing_without_times").entries.single()

        assertEquals(62734, entry.malId)
        assertEquals(24, entry.total)
        assertEquals(emptyList<Any>(), entry.episodes)
        assertFalse(entry.moreEpisodes)
    }

    @Test
    fun `a title not yet aired without a time is an entry with no episodes`() {
        val entry = page("not_yet_aired_without_time").entries.single()

        assertEquals(62841, entry.malId)
        assertEquals(emptyList<Any>(), entry.episodes)
    }

    @Test
    fun `an unknown id alone is a page with no entry, not a failure`() {
        val page = page("unknown_alone")

        assertEquals(emptyList<Any>(), page.entries)
        assertEquals(emptySet<Int>(), page.unreadable)
        assertFalse(page.moreTitles)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `an unknown id among others is left out and the others are read`() {
        assertEquals(listOf(61987, 62734), page("unknown_among_others").entries.map { it.malId })
    }

    @Test
    fun `a title without a total and with more episodes says so`() {
        val entry = page("hundreds_of_episodes").entries.single()

        assertNull(entry.total)
        assertTrue(entry.moreEpisodes)
        assertEquals(nodes("hundreds_of_episodes"), entry.episodes.map { it.number to it.airsAt })
    }

    @Test
    fun `two entries for one MAL id are both read, in AniList's order`() {
        val entries = page("totals_differ_by_more").entries

        assertEquals(listOf(51039, 51039), entries.map { it.malId })
        assertEquals(listOf(52, 62), entries.map { it.total })
        assertEquals(listOf(1, 24), entries.map { it.episodes.first().number })
    }

    @Test
    fun `more titles than a page holds say so on every page but the last`() {
        assertTrue(page("more_than_one_page").moreTitles)
        assertEquals(50, page("more_than_one_page").entries.size)
        assertFalse(page("more_than_one_page_2").moreTitles)
    }

    @Test
    fun `an episode numbered 0 or below is dropped`() {
        val zero = read("airing_weekly", "0", "airingSchedule", "nodes", "0", "episode", value = JsonPrimitive(0))!!
        val below = read("airing_weekly", "0", "airingSchedule", "nodes", "1", "episode", value = JsonPrimitive(-1))!!

        assertEquals(
            (2..12).toList(),
            zero.entries
                .single()
                .episodes
                .map { it.number },
        )
        assertEquals(
            listOf(1) + (3..12),
            below.entries
                .single()
                .episodes
                .map { it.number },
        )
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `a total of 0 is no total`() {
        assertNull(read("airing_weekly", "0", "episodes", value = JsonPrimitive(0))!!.entries.single().total)
    }

    @Test
    fun `an entry that cannot be read names its MAL id, the others are read, and it is reported`() {
        val page = read("unknown_among_others", "0", "airingSchedule", "nodes", "3", "airingAt", value = JsonPrimitive("soon"))!!

        assertEquals(setOf(61987), page.unreadable)
        assertEquals(listOf(62734), page.entries.map { it.malId })
        assertEquals(listOf("media"), reported.map { it.message })
    }

    @Test
    fun `an air time no schedule can hold makes the entry unreadable`() {
        val page = read("airing_weekly", "0", "airingSchedule", "nodes", "0", "airingAt", value = JsonPrimitive(Long.MAX_VALUE))!!

        assertEquals(setOf(61987), page.unreadable)
    }

    @Test
    fun `an entry without a readable MAL id makes the whole page unreadable`() {
        assertNull(read("unknown_among_others", "1", "idMal", value = null))
        assertEquals(listOf("idMal"), reported.map { it.message })
    }

    @Test
    fun `an answer with errors or without its page is unreadable and reported, one that is not JSON is not reported`() {
        assertNull(read(JsonObject(json("airing_weekly") + ("errors" to JsonArray(emptyList()))).toString()))
        assertNull(read(json("airing_weekly").edited(listOf("data", "Page"), value = null).toString()))
        assertEquals(2, reported.size)
        assertNull(read("<html>Sign in to the network</html>"))
        assertEquals(2, reported.size)
    }

    private companion object {
        val MEDIA = listOf("data", "Page", "media")
    }
}

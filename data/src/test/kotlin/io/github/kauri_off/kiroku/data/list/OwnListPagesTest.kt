package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

// The list's mappers on the pages of the signed-in user's own list as MAL sent them; skipped where they are absent.
class OwnListPagesTest {
    private val names = (1..3).map { "own_list_$it" }

    private fun page(name: String): ListPage {
        val answer = OwnerRecordings.named(name)
        assertTrue("$name is not an answer of 200", answer.status == 200)
        return OwnerRecordings.read(name) { MalJson.parseToJsonElement(answer.body).jsonObject.toListPage(Repairs()) }
    }

    @Test
    fun `every entry of the user's own pages is read, with nothing left out or filled in`() {
        val pages = names.associateWith(::page)

        assertTrue("own_list_1 holds no entry", pages.getValue("own_list_1").entries.isNotEmpty())
        for ((name, page) in pages) assertTrue("$name repaired ${page.repaired}", page.repaired.isEmpty())
    }

    @Test
    fun `each page of the user's own list names the next by its offset, and no title is on two`() {
        val pages = names.map(::page)
        var offset = 0
        for ((name, page) in names.zip(pages)) {
            offset += page.entries.size
            page.next?.let { assertTrue("$name names another next offset than its last entry's", it == offset) }
        }
        val titles = pages.flatMap { page -> page.entries.map { it.anime.id } }
        assertTrue("a title is on two pages", titles.size == titles.toSet().size)
    }
}

package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import org.junit.Assert.assertTrue
import org.junit.Test

// The mapper on the titles of the signed-in recorder's answers as MAL sent them; skipped where they are absent.
class SignedInTitlesTest {
    @Test
    fun `every title of the signed-in answers is read, with nothing left out or filled in`() {
        for (name in listOf("entry_absent", "entry_full", "own_list_1", "own_list_2", "own_list_3")) {
            val answer = OwnerRecordings.named(name)
            val nodes = OwnerRecordings.read(name) { titleNodesIn(MalJson.parseToJsonElement(answer.body)) }
            assertTrue("$name holds no title", nodes.isNotEmpty())
            for ((index, node) in nodes.withIndex()) {
                val repaired = OwnerRecordings.read(name) { Repairs().also { node.toNode().toAnime(it) }.fields }
                assertTrue("$name, title $index: repaired $repaired", repaired.isEmpty())
            }
        }
    }
}

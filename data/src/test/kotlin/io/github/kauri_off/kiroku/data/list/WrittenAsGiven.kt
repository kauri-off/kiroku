package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.list.sent
import io.github.kauri_off.kiroku.core.model.EntryStatus
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue

// Each entry of the list page [name], given as MAL sent it, written back unchanged carries each value in the form MAL gave
// it. Read and decoded inside [OwnerRecordings.read]: a failure names the page, the entry's place and the field, never a value.
internal fun assertWrittenAsGiven(
    name: String,
    body: String,
) = OwnerRecordings.read(name) { assertWrittenAsGiven(name, MalJson.parseToJsonElement(body).jsonObject) }

private fun assertWrittenAsGiven(
    name: String,
    page: JsonObject,
) {
    val entries = page.toListPage(Repairs()).entries
    val given = page.getValue("data").jsonArray.map { it.jsonObject.getValue("list_status").jsonObject }
    assertTrue("$name: entries left out", given.size == entries.size)
    for ((index, pair) in entries.zip(given).withIndex()) {
        val (entry, status) = pair
        val form = entry.values.sent.form()
        val sent =
            mapOf(
                "status" to "status",
                "score" to "score",
                "num_episodes_watched" to "num_watched_episodes",
                "start_date" to "start_date",
                "finish_date" to "finish_date",
            )
        for ((field, key) in sent) {
            // A status the app does not know is left out, so MAL keeps the one it gave.
            if (field == "status" && entry.status is EntryStatus.Unrecognized) {
                assertTrue("$name, entry $index: status sent", key !in form)
                continue
            }
            val text = (status[field] as? JsonPrimitive)?.content.orEmpty()
            assertTrue("$name, entry $index: $field", form[key] == text)
        }
    }
}

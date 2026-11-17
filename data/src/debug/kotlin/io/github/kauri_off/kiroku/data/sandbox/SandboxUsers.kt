package io.github.kauri_off.kiroku.data.sandbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

// The users the sandbox's MAL answers for besides its signed-in one, each by name in any letter case.
object SandboxUsers {
    // Their list holds every title of the sandbox [COPIES] times: the title itself, and copies of it under other ids.
    const val PUBLIC = "Sandbox_Public"
    const val PRIVATE = "Sandbox_Private"

    const val COPIES = 30

    // A copy's id is its title's plus a multiple of this, which is above every id MAL has given.
    private const val ID_STRIDE = 1_000_000L

    private val UPDATED = Instant.parse("2001-01-01T00:00:00Z")

    // A title of the sandbox by [id], a copy's included, from [title]'s answers for the titles the sandbox has.
    fun titleOf(
        id: Long,
        title: (Long) -> JsonObject?,
    ): JsonObject? {
        title(id)?.let { return it }
        val copy = (id / ID_STRIDE).toInt().takeIf { it in 1 until COPIES } ?: return null
        val page = title(id % ID_STRIDE) ?: return null
        return JsonObject(page + mapOf("id" to JsonPrimitive(id), "title" to JsonPrimitive("${page.text("title")} #${copy + 1}")))
    }

    // The public user's list over [titles]: made-up values, the same on every run, each fitting its title's status and total.
    fun publicList(titles: Map<Long, JsonObject>): Map<Long, Listed> {
        val bases = titles.entries.sortedBy { it.key }
        val list = mutableMapOf<Long, Listed>()
        for (copy in 0 until COPIES) {
            for ((id, title) in bases) list[id + copy * ID_STRIDE] = listed(title, list.size)
        }
        return list
    }

    private fun listed(
        title: JsonObject,
        i: Int,
    ): Listed {
        val total = title.episodes
        val updated = UPDATED.plusSeconds(i * 3600L)
        val start = START_DATES[i % START_DATES.size]
        val score = (i * 7) % 11

        fun entry(
            status: String,
            watched: Int,
            score: Int = 0,
            start: String? = null,
            finish: String? = null,
        ) = Listed(status, score, watched, start, finish, updated)
        return when (title.text("status")) {
            "not_yet_aired" -> entry("plan_to_watch", 0)
            "currently_airing" ->
                when (i % 3) {
                    0 -> entry("watching", minOf(i % 5, total ?: Int.MAX_VALUE), score, start)
                    1 -> entry("dropped", 1, score)
                    else -> entry("plan_to_watch", 0)
                }
            else ->
                when (i % 10) {
                    in 0..3 -> entry("completed", total ?: UNKNOWN_TOTAL, score, start, "2001-12-31".takeIf { start != null })
                    4 -> entry("watching", (total ?: UNKNOWN_TOTAL) / 2, start = start)
                    5 -> entry("on_hold", (total ?: UNKNOWN_TOTAL) / 3, score, start)
                    6 -> entry("dropped", 1, score)
                    else -> entry("plan_to_watch", 0)
                }
        }
    }

    private const val UNKNOWN_TOTAL = 12
    private val START_DATES = listOf("2001-02-03", "2001-04", "2001", null)
}

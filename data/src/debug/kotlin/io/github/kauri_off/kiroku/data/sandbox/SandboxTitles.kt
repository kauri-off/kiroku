package io.github.kauri_off.kiroku.data.sandbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

// The titles of the sandbox: MAL's own, from the answers the client-id recorder committed, with no picture of MAL's.
object SandboxTitles {
    // Public answers whose titles the sandbox knows: no user's list, and no title not safe for work.
    val Answers =
        listOf(
            "title_airing",
            "title_film",
            "title_finished",
            "title_no_related",
            "title_not_yet_aired",
            "title_unknown_episode_count",
            "ranking_airing",
            "ranking_upcoming",
        )

    private val json = Json { ignoreUnknownKeys = true }

    // Every title of [answers], by id, the fullest copy of each; [read] gives an answer's body by its name.
    fun read(
        read: (String) -> String,
        poster: (Long) -> String,
        answers: List<String> = Answers,
    ): Map<Long, JsonObject> {
        val titles = mutableMapOf<Long, JsonObject>()

        fun add(node: JsonElement?) {
            val title = (node as? JsonObject)?.let { pictured(it, poster) } ?: return
            val id = title.id ?: return
            if (title.size > (titles[id]?.size ?: -1)) titles[id] = title
        }
        for (name in answers) {
            val answer = json.parseToJsonElement(read(name)).jsonObject
            val data = answer["data"] as? JsonArray
            if (data == null) add(answer) else data.forEach { add((it as? JsonObject)?.get("node")) }
            for (list in listOf("related_anime", "recommendations")) {
                (answer[list] as? JsonArray)?.forEach { add((it as? JsonObject)?.get("node")) }
            }
        }
        return titles
    }

    // The list the sandbox starts with, from MAL's [titles] as of [now]: every value made up, in each of MAL's five statuses,
    // and every title airing or finished within the last month in Watching.
    fun startingList(
        titles: Map<Long, JsonObject>,
        now: Instant,
    ): Map<Long, Listed> {
        val byTitle = titles.entries.sortedBy { it.key }
        val recent = byTitle.filter { it.value.endedWithin(now) }
        val airing = byTitle.filter { it.value.text("status") == "currently_airing" } + recent
        val coming = byTitle.filter { it.value.text("status") == "not_yet_aired" }.take(COMING)
        val finished = byTitle.filter { it.value.text("status") == "finished_airing" && it !in recent }.take(FINISHED)
        val list = mutableMapOf<Long, Listed>()
        var age = 0L

        fun put(
            id: Long,
            status: String,
            watched: Int,
            score: Int = 0,
            start: String? = null,
            finish: String? = null,
        ) {
            list[id] = Listed(status, score, watched, start, finish, now.truncatedTo(ChronoUnit.SECONDS).minus(++age, ChronoUnit.DAYS))
        }
        airing.forEachIndexed { i, (id, title) ->
            val watched =
                if (recent.any { it.key == id }) {
                    (title.episodes ?: 1) - 1
                } else {
                    minOf(
                        i % AIRING_KINDS,
                        title.episodes ?: Int.MAX_VALUE,
                    )
                }
            put(id, "watching", watched, start = LocalDate.of(2001, 1, 1).plusDays(i.toLong()).toString())
        }
        coming.forEach { (id, _) -> put(id, "plan_to_watch", 0) }
        finished.forEachIndexed { i, (id, title) ->
            val total = title.episodes ?: UNKNOWN_TOTAL
            when (i % FINISHED_KINDS) {
                0 -> put(id, "completed", total, score = 1 + i % 10, start = "2001-02-01", finish = "2001-03-01")
                1 -> put(id, "completed", total, score = 1 + i % 10, start = "2001-04")
                2 -> put(id, "on_hold", total / 2, start = "2001")
                3 -> put(id, "dropped", 1, score = 1)
                else -> put(id, "plan_to_watch", 0)
            }
        }
        return list
    }

    // MAL's pictures replaced by the stand-in art's address, in the title and every title inside it.
    private fun pictured(
        node: JsonObject,
        poster: (Long) -> String,
    ): JsonObject {
        val id = node.id
        return JsonObject(
            node.filterKeys { it != "pictures" }.mapValues { (key, value) ->
                when {
                    key == "main_picture" && id != null ->
                        JsonObject(
                            mapOf(
                                "medium" to JsonPrimitive(poster(id)),
                                "large" to JsonPrimitive(poster(id)),
                            ),
                        )
                    value is JsonObject -> pictured(value, poster)
                    value is JsonArray -> JsonArray(value.map { (it as? JsonObject)?.let { inner -> pictured(inner, poster) } ?: it })
                    else -> value
                }
            },
        )
    }

    private const val AIRING_KINDS = 3
    private const val COMING = 3
    private const val FINISHED = 20
    private const val FINISHED_KINDS = 5
    private const val UNKNOWN_TOTAL = 12
}

internal val JsonObject.id: Long? get() = (get("id") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }

// A known total; MAL's 0 is none.
internal val JsonObject.episodes: Int? get() = (get("num_episodes") as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private val RECENT = Duration.ofDays(31)

// MAL marks the title finished, on a full end date within the month before [now].
private fun JsonObject.endedWithin(now: Instant): Boolean {
    if (text("status") != "finished_airing") return false
    val end = text("end_date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
    return !end.atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(now.minus(RECENT))
}

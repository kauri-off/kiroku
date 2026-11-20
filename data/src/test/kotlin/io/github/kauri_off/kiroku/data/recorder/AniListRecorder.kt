package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.anilist.AniListSchedule
import io.github.kauri_off.kiroku.data.network.UserAgent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

// A third of the 30 requests a minute AniList allows while degraded, and a ninth of its usual 90.
internal const val ANILIST_PAUSE_MILLIS = 6000L

// The note beside the answers: the limit AniList reported with them.
internal const val LIMIT_NOTE = "limit.txt"

// More pages than this of one request stop the run.
internal const val ANILIST_MOST_PAGES = 12

internal fun aniListCalls(): Call.Factory = OkHttpClient.Builder().addInterceptor(UserAgent("Kiroku-recorder")).build()

// Sends the requests one at a time, each page after the last; stores each answer's status and body, never a header.
internal class AniListRecorder(
    private val calls: Call.Factory,
    private val address: HttpUrl,
    private val into: File,
    // Where a run's answers wait until the run has ended: a run that stops replaces nothing in [into].
    private val staging: File,
    private val today: LocalDate,
    private val say: (String) -> Unit,
    private val pause: () -> Unit,
) {
    private val asked = answerTo(AniListSchedule.QUERY)
    private val rechecked = answerTo(AniListSchedule.RECHECK)
    private val limits = sortedSetOf<String>()

    var sent = 0
        private set

    // Stops at the first answer that is not a 200 holding only what was asked.
    fun record(asks: List<AniListAsk>) {
        require(staging.canonicalFile != into.canonicalFile)
        emptied(staging)
        try {
            asks.forEach(::ask)
        } catch (stop: RecorderStop) {
            throw RecorderStop("${stop.message}\nNo stored answer was replaced; what this run was answered is in $staging")
        }
        if (limits.isNotEmpty()) {
            File(staging, LIMIT_NOTE).writeText("Requests a minute AniList reported with its answers of $today: ${limits.joinToString()}\n")
        }
        keep(asks)
    }

    private fun ask(ask: AniListAsk) {
        var page = 1
        var schedulePage = 1
        for (number in 1..ANILIST_MOST_PAGES) {
            val file = if (number == 1) ask.name else "${ask.name}_$number"
            val answer = store(file, AniListSchedule.body(ask.ids, page, schedulePage, ask.recheck), if (ask.recheck) rechecked else asked)
            when {
                answer.more("Page") -> page++
                !ask.recheck && ask.ids.size == 1 && answer.more("airingSchedule") -> schedulePage++
                else -> return
            }
        }
        throw RecorderStop("${ask.name}: more than $ANILIST_MOST_PAGES pages, stopped")
    }

    private fun store(
        file: String,
        body: String,
        selection: Selection,
    ): JsonElement {
        val request =
            Request
                .Builder()
                .url(address)
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        if (sent++ > 0) pause()
        val (status, text) =
            try {
                calls.newCall(request).execute().use { response ->
                    response.header("X-RateLimit-Limit")?.let(limits::add)
                    response.code to response.body.string()
                }
            } catch (e: IOException) {
                throw RecorderStop("$file: no answer (${e.javaClass.simpleName}), stopped")
            }
        if (status != 200) throw RecorderStop("$file: AniList answered $status, stopped")
        val json =
            try {
                Json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                throw RecorderStop("$file: the answer is not JSON, stopped")
            }
        val unasked = selection.unasked(json)
        if (unasked.isNotEmpty()) throw RecorderStop("$file: the answer holds what was not asked (${unasked.distinct()}), stopped")
        File(staging, "$file.$status.json").writeText(Pretty.encodeToString(JsonElement.serializer(), json) + "\n")
        say("$file: $status, ${text.length} characters")
        return json
    }

    // Whether any [field] of the answer says there is a next page.
    private fun JsonElement.more(field: String): Boolean =
        when (this) {
            is JsonObject ->
                entries.any { (key, value) ->
                    val next = ((value as? JsonObject)?.get("pageInfo") as? JsonObject)?.get("hasNextPage") as? JsonPrimitive
                    (key == field && next?.booleanOrNull == true) || value.more(field)
                }
            is JsonArray -> any { it.more(field) }
            else -> false
        }

    // A run that has ended: every earlier page of its requests goes, its answers and its note take their place.
    private fun keep(asks: List<AniListAsk>) {
        into.mkdirs()
        val names = asks.map { it.name }.toSet()
        into
            .listFiles { earlier -> pageOf(earlier.name) in names }
            ?.forEach { it.delete() }
        for (answer in staging.listFiles().orEmpty().sortedBy { it.name }) {
            Files.move(answer.toPath(), File(into, answer.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        staging.delete()
    }

    private companion object {
        val Pretty = Json { prettyPrint = true }
    }
}

// The request a stored answer's file belongs to: its name without the status, the kind and a page's number.
internal fun pageOf(file: String): String = file.substringBefore('.').replace(Regex("""_\d+$"""), "")

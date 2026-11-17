package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.tenrai.TenraiStatistics
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

// Twenty requests a minute, a sixth of the 120 Tenrai allows, and far from its 4 a second.
internal const val TENRAI_PAUSE_MILLIS = 3000L

// The note beside the answers: the limit and cache headers Tenrai sent with them.
internal const val HEADERS_NOTE = "headers.txt"

// Statuses that answer a case; any other is stored and stops the run.
private val CaseStatuses = setOf(200, 404)

// OkHttp's own User-Agent: generic, naming neither this project nor anyone.
internal fun tenraiCalls(): Call.Factory = OkHttpClient()

// Sends the requests one at a time; stores each answer's status and body, and of its headers only those of the note.
internal class TenraiRecorder(
    private val calls: Call.Factory,
    private val address: HttpUrl,
    private val into: File,
    // Where a run's answers wait until the run has ended: a run that stops replaces nothing in [into].
    private val staging: File,
    private val today: LocalDate,
    private val say: (String) -> Unit,
    private val pause: () -> Unit,
) {
    private val noted = sortedMapOf<String, MutableSet<String>>()

    var sent = 0
        private set

    fun record(asks: List<TenraiAsk>) {
        require(staging.canonicalFile != into.canonicalFile)
        emptied(staging)
        try {
            asks.forEach(::ask)
        } catch (stop: RecorderStop) {
            throw RecorderStop("${stop.message}\nNo stored answer was replaced; what this run was answered is in $staging")
        } finally {
            if (noted.isNotEmpty()) {
                File(staging, HEADERS_NOTE).writeText(
                    "Limit and cache headers Tenrai sent with its answers of $today:\n" +
                        noted.entries.joinToString("") { (name, values) -> "$name: ${values.joinToString(" | ")}\n" },
                )
            }
        }
        keep(asks)
    }

    private fun ask(ask: TenraiAsk) {
        val request =
            Request
                .Builder()
                .url(TenraiStatistics.url(ask.id, address))
                .header("Accept", "application/json")
                .build()
        if (sent++ > 0) pause()
        val (status, text) =
            try {
                calls.newCall(request).execute().use { response ->
                    for ((name, value) in response.headers) {
                        if (NotedHeader.matches(name)) noted.getOrPut(name.lowercase()) { sortedSetOf() } += value
                    }
                    response.code to response.body.string()
                }
            } catch (e: IOException) {
                throw RecorderStop("${ask.name}: no answer (${e.javaClass.simpleName}), stopped")
            }
        val json =
            try {
                Json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                File(staging, "${ask.name}.$status.txt").writeText(text)
                throw RecorderStop("${ask.name}: Tenrai answered $status, not JSON, stopped")
            }
        File(staging, "${ask.name}.$status.json").writeText(Pretty.encodeToString(JsonElement.serializer(), json) + "\n")
        say("${ask.name}: $status, ${text.length} characters")
        if (status !in CaseStatuses) throw RecorderStop("${ask.name}: Tenrai answered $status, stopped")
    }

    // A run that has ended: every earlier answer of its requests goes, its answers and its note take their place.
    private fun keep(asks: List<TenraiAsk>) {
        into.mkdirs()
        val names = asks.map { it.name }.toSet()
        into.listFiles { earlier -> earlier.name.substringBefore('.') in names }?.forEach { it.delete() }
        for (answer in staging.listFiles().orEmpty().sortedBy { it.name }) {
            Files.move(answer.toPath(), File(into, answer.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        staging.delete()
    }

    private companion object {
        val Pretty = Json { prettyPrint = true }
        val NotedHeader = Regex("(?i)(x-)?ratelimit.*|retry-after|cache-control|age|expires|last-modified")
    }
}

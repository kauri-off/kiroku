package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.data.tenrai.TenraiStatistics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

// Tenrai on the device: a title's statistics as Tenrai answered them for the recorded titles, every other title its
// recorded "no record" under that title's id. [read] gives a recorded answer's body by its file name.
class SandboxTenrai(
    private val read: (String) -> String,
) {
    private val address = TenraiStatistics.ADDRESS.toHttpUrl()

    fun answers(url: HttpUrl): Boolean = url.host == address.host && url.port == address.port

    fun answer(request: Request): SandboxAnswer {
        val path = request.url.encodedPath.removePrefix(address.encodedPath)
        val parts = path.split('/')
        val id = parts.takeIf { it.size == 3 && it[0] == "anime" && it[2] == "statistics" }?.get(1)?.toLongOrNull()
        if (request.method != "GET" || id == null) return SandboxAnswer(404, NO_ROUTE)
        Recorded[id]?.let { return SandboxAnswer(200, read(it)) }
        val unknown = Json.parseToJsonElement(read(NO_RECORD)).jsonObject
        val named =
            unknown.mapValues { (key, value) ->
                when (key) {
                    "message" -> JsonPrimitive("Anime with ID $id not found.")
                    "path" -> JsonPrimitive(request.url.encodedPath)
                    else -> value
                }
            }
        return SandboxAnswer(404, JsonObject(named).toString())
    }

    companion object {
        // The titles Tenrai was asked for, by MAL id, each with its answer's file among the recorded ones.
        val Recorded: Map<Long, String> =
            mapOf(
                1L to "popular.200.json",
                61987L to "airing.200.json",
                65189L to "few_votes.200.json",
                59068L to "not_yet_aired.200.json",
            )
        const val NO_RECORD = "unknown.404.json"

        // Every recorded answer the sandbox reads, which the debug and preview builds hold as assets.
        val Files: List<String> = Recorded.values.sorted() + NO_RECORD

        private const val NO_ROUTE = """{"status":404,"type":"NotFoundException","message":"","error":"","path":""}"""
    }
}

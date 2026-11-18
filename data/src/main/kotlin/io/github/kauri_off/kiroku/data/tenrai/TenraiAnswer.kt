package io.github.kauri_off.kiroku.data.tenrai

import io.github.kauri_off.kiroku.core.score.ScoreDistribution
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// What of an answer of Tenrai's could not be read, never quoting a value.
class TenraiOddAnswer(
    what: String,
) : Exception(what)

// The votes per score of a title's statistics; a score left out has none. Null when the answer holds none that can be
// read, reported unless it is not JSON at all, as a portal's page is not.
internal fun tenraiDistribution(
    body: String,
    report: (TenraiOddAnswer) -> Unit,
): ScoreDistribution? {
    val answer = parsed(body) ?: return null
    val scores = ((answer as? JsonObject)?.get("data") as? JsonObject)?.get("scores") as? JsonArray
    if (scores == null) {
        report(TenraiOddAnswer("scores"))
        return null
    }
    val votes = mutableMapOf<Int, Int>()
    for (item in scores) {
        val score = item.int("score")
        val count = item.int("votes")
        if (score == null || count == null || score !in ScoreDistribution.SCORES || count < 0 || score in votes) {
            report(TenraiOddAnswer("score"))
            return null
        }
        votes[score] = count
    }
    return ScoreDistribution(votes)
}

// A 404 that says Tenrai has no record of what was asked, not that the address is not its API's.
internal fun tenraiHasNoRecord(body: String): Boolean =
    ((parsed(body) as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == NO_RECORD

private fun parsed(body: String): JsonElement? =
    try {
        Json.parseToJsonElement(body)
    } catch (_: SerializationException) {
        null
    }

private fun JsonElement.int(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private const val NO_RECORD = "ResourceNotFoundException"

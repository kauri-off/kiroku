package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The fields a GraphQL query selects, each with what it selects in turn; null for a value.
internal class Selection(
    val fields: Map<String, Selection?>,
) {
    // Where [json] holds a field this selection does not ask for, or an object and a value where the other is asked.
    fun unasked(
        json: JsonElement,
        path: String = "",
    ): List<String> =
        when (json) {
            is JsonArray -> json.flatMap { unasked(it, path) }
            is JsonObject ->
                json.flatMap { (key, value) ->
                    val inner = fields[key]
                    when {
                        key !in fields -> listOf("$path/$key")
                        inner == null -> if (value is JsonPrimitive) emptyList() else listOf("$path/$key")
                        else -> inner.unasked(value, "$path/$key")
                    }
                }
            is JsonNull -> emptyList()
            else -> listOf(path)
        }
}

// What [query] selects, without its arguments, an aliased field under its alias; fragments are not read.
internal fun selection(query: String): Selection {
    val body = query.substring(query.indexOf('{')).replace(Regex("""\([^()]*\)"""), "")
    val tokens = Regex("""[A-Za-z_]\w*|[{}:]""").findAll(body).map { it.value }.toList()
    var at = 1

    // Reads from just after a `{` to just after its `}`.
    fun block(): Selection {
        val fields = linkedMapOf<String, Selection?>()
        while (tokens[at] != "}") {
            val name = tokens[at++]
            if (tokens[at] == ":") at += 2
            fields[name] =
                if (tokens[at] == "{") {
                    at++
                    block()
                } else {
                    null
                }
        }
        at++
        return Selection(fields)
    }
    check(tokens.first() == "{")
    return block()
}

// What an answer to [query] may hold: its data and nothing else.
internal fun answerTo(query: String): Selection = Selection(mapOf("data" to selection(query)))

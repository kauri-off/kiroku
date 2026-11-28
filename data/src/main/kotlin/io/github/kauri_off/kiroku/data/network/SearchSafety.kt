package io.github.kauri_off.kiroku.data.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonElement?.searchSafety(): Boolean? =
    when ((this as? JsonPrimitive)?.content) {
        "white" -> true
        "gray", "black" -> false
        else -> null
    }

package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class MangaMutationMethod { Patch, Delete }

fun interface MangaMutationObserver {
    fun candidate(
        owner: SignInId,
        id: MangaId,
        method: MangaMutationMethod,
    ): MangaMutationClaim?
}

fun interface MangaMutationClaim {
    fun claim(): MangaMutationExchange?
}

abstract class MangaMutationExchange internal constructor() : AbstractCoroutineContextElement(Key) {
    internal var confirmed = false

    internal abstract fun write(
        requested: MangaId,
        answer: MangaListWriteAnswer,
        form: Map<String, String>,
        authorization: String,
        clientId: String,
    )

    internal abstract fun page(
        body: String,
        entries: List<MangaListRow>,
        last: Boolean,
        authorization: String,
        clientId: String,
    )

    internal abstract suspend fun finish()

    companion object Key : CoroutineContext.Key<MangaMutationExchange>
}

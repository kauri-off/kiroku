package io.github.kauri_off.kiroku.wiring

import android.content.Context
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import java.time.Clock

interface AppGraph {
    val clock: Clock
    val session: SessionRepository
    val list: ListRepository
    val anime: AnimeRepository
    val airing: AiringRepository
    val search: SearchRepository
    val user: UserRepository

    // The "Title language" setting, Romaji until Settings exist.
    val titleLanguage: TitleLanguage get() = TitleLanguage.Romaji
}

interface AppGraphOwner {
    val graph: AppGraph
}

val Context.appGraph: AppGraph get() = (applicationContext as AppGraphOwner).graph

internal typealias DemoGraphFactory = (Clock, CoroutineScope) -> AppGraph

internal fun malClientId(value: String): String? = value.trim().ifEmpty { null }

internal fun createGraph(
    clientId: String,
    clock: Clock,
    scope: CoroutineScope,
    demo: DemoGraphFactory? = demoGraphFactory,
): AppGraph = if (malClientId(clientId) == null && demo != null) demo(clock, scope) else LiveGraph(clock)

package io.github.kauri_off.kiroku.wiring

import android.content.Context
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserRepository
import io.github.kauri_off.kiroku.notShown
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

// The graph of the source a debug or preview build was told to start on, or null for the real account.
internal typealias OtherSource = (context: Context, clientId: String, clock: Clock, scope: CoroutineScope) -> AppGraph?

internal fun malClientId(value: String): String? = value.trim().ifEmpty { null }

internal val LOG_TAG = notShown("Kiroku")

// The graph of this process, on the source chosen before it started; a release build has the real account alone.
internal fun createGraph(
    context: Context,
    clientId: String,
    clock: Clock,
    scope: CoroutineScope,
    other: OtherSource? = otherSource,
    live: () -> AppGraph = { LiveGraph(LiveParts(context, LiveSource.Account, clientId, clock, scope, reportUnexpected, requestLog)) },
): AppGraph = other?.invoke(context, clientId, clock, scope) ?: live()

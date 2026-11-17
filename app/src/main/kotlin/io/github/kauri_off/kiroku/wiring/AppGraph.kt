package io.github.kauri_off.kiroku.wiring

import android.content.Context
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.core.repository.SearchRepository
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.repository.UserRepository
import io.github.kauri_off.kiroku.core.score.ScoreDistributionRepository
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import java.time.Clock

interface AppGraph {
    val clock: Clock
    val session: SessionRepository
    val list: ListRepository
    val anime: AnimeRepository
    val airing: AiringRepository

    // A title's score distribution, from a service apart from MAL's pages.
    val scores: ScoreDistributionRepository
    val search: SearchRepository
    val user: UserRepository
    val connection: Connection
    val preferences: PreferencesRepository

    // Where a view model builds its state, off the main thread.
    val background: CoroutineDispatcher get() = Dispatchers.Default

    // The HTTP client images are fetched through; a graph without one reaches no host for them.
    val images: Call.Factory? get() = null

    // The "Title language" setting, Romaji until Settings exist.
    val titleLanguage: TitleLanguage get() = TitleLanguage.Romaji

    // True for made-up titles and users, as the demo and the sandbox have, which a screen not built yet may stand in with.
    val madeUp: Boolean get() = false
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

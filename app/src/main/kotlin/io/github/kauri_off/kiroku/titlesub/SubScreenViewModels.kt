package io.github.kauri_off.kiroku.titlesub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.ShownLoads
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeDetails
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AdultSearchChoice
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.score.ScoreDistribution
import io.github.kauri_off.kiroku.core.score.ScoreDistributionRepository
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.recommendationsNeedRefresh
import io.github.kauri_off.kiroku.stale
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.title.airingOf
import io.github.kauri_off.kiroku.title.entriesOf
import io.github.kauri_off.kiroku.title.knownAiring
import io.github.kauri_off.kiroku.title.knownEntries
import io.github.kauri_off.kiroku.title.withRead
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

// One title, its details and whether loading them failed, the summary standing in for what is not stored.
abstract class SubScreenViewModel(
    private val id: AnimeId,
    private val anime: AnimeRepository,
    session: SessionRepository,
    connection: Connection,
    private val clock: Clock,
    private val opened: TitleSummary?,
    private val adultSearchChoice: Flow<AdultSearchChoice>? = null,
) : ViewModel() {
    protected class Title(
        val anime: Anime?,
        val details: AnimeDetails?,
        val session: Session?,
        val failure: DataError?,
        // The title's own store could not be read.
        val storeFailure: DataError? = null,
    ) {
        constructor(opened: TitleSummary) : this(opened.anime, opened.page?.details, opened.session, null)
    }

    private class Stored(
        val anime: Anime? = null,
        val details: AnimeDetails? = null,
        val failure: DataError? = null,
    )

    private val failure = MutableStateFlow<DataError?>(null)
    protected val refreshing = MutableStateFlow(false)
    private var load: Job? = null
    private var replaceObsolete = false

    // Retry reads again what could not be read.
    protected val retries = MutableStateFlow(0)

    // A store that cannot be read fails the screen, as on the title page.
    private val stored =
        combine(anime.observeAnime(id), anime.observeDetails(id)) { anime, details -> Stored(anime, details) }
            .readAgainOnRetry(retries) { Stored(failure = it) }

    protected val title: Flow<Title> =
        combine(stored, session.observeSession(), failure) { stored, session, failure ->
            Title(stored.anime ?: opened?.anime, stored.details ?: opened?.page?.details, session, failure, stored.failure)
        }

    private val loads =
        ShownLoads(
            viewModelScope,
            connection,
            ::refreshIfStale,
            ::refresh,
            shownAgain = ::shownAgain,
        )

    private fun shownAgain() {
        if (adultSearchChoice == null) return
        viewModelScope.launch {
            val needed = replaceObsolete || recommendationsNeedRefresh()
            if (needed && loads.isShowing && load?.isActive != true) refresh()
        }
    }

    protected var recommendationFilter: AdultSearchChoice? = null
        private set

    protected fun filterChanged(choice: AdultSearchChoice) {
        val previous = recommendationFilter
        recommendationFilter = choice
        if (previous != null && previous != choice) {
            failure.value = null
            if (loads.isShowing) refresh()
        }
    }

    // Collected while the screen is shown; showing it refreshes stale details.
    val shown: StateFlow<Unit> get() = loads.shown

    private suspend fun refreshIfStale() {
        val stale = anime.stale(id, opened?.anime, clock.instant()) || replaceObsolete || recommendationsNeedRefresh()
        if (stale && load?.isActive != true) refresh()
    }

    private suspend fun recommendationsNeedRefresh(): Boolean =
        adultSearchChoice?.first()?.let { anime.recommendationsNeedRefresh(id, it.enabled) } == true

    protected fun retry() {
        retries.update { it + 1 }
        refresh()
    }

    protected open fun refreshFailed(error: DataError) = Unit

    // A newer load replaces an older one, so the older one's end never stands for the screen.
    private fun refresh() {
        load?.cancel()
        replaceObsolete = false
        failure.value = null
        refreshing.value = true
        loads.started()
        load =
            viewModelScope.launch {
                val choice = adultSearchChoice?.first()
                val result = anime.refresh(id)
                if (choice != adultSearchChoice?.first()) {
                    currentCoroutineContext().ensureActive()
                    refreshing.value = false
                    if ((result as? Outcome.Failure)?.error != DataError.ListEnded) {
                        if (loads.isShowing) refresh() else replaceObsolete = true
                    }
                    return@launch
                }
                val error =
                    when (result) {
                        is Outcome.Failure -> result.error
                        is Outcome.Success -> DataError.ServerError.takeIf { recommendationsNeedRefresh() }
                    }
                currentCoroutineContext().ensureActive()
                failure.value = error
                refreshing.value = false
                if (error != null) refreshFailed(error)
                loads.ended(error)
            }
    }
}

class RelatedViewModel(
    id: AnimeId,
    anime: AnimeRepository,
    list: ListSource,
    airing: AiringRepository,
    session: SessionRepository,
    connection: Connection,
    private val clock: Clock,
    titleLanguage: Flow<TitleLanguage>,
    opened: TitleSummary? = null,
) : SubScreenViewModel(id, anime, session, connection, clock, opened) {
    private class ListRead(
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
        val failure: DataError? = null,
    )

    private class Sources(
        val title: Title,
        val list: ListRead,
        val airing: Map<AnimeId, Airing>,
    ) {
        constructor(opened: TitleSummary) : this(Title(opened), ListRead(opened.knownEntries()), opened.knownAiring())
    }

    // Of the list and the airing, only the related titles; a list that cannot be read fails the screen.
    private val sources: StateFlow<Sources?> =
        title
            .withRead({
                it.details
                    ?.related
                    .orEmpty()
                    .map { related -> related.anime.id }
                    .toSet()
            }) { ids ->
                combine(
                    list.entriesOf(ids).map { ListRead(it) }.readAgainOnRetry(retries) { ListRead(failure = it) },
                    airing.airingOf(ids).readAgainOnRetry(retries) { emptyMap() },
                    ::Pair,
                )
            }.map { (title, around) -> Sources(title, around.first, around.second) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), opened?.let(::Sources))

    val state: StateFlow<RelatedState> =
        combine(sources.filterNotNull(), titleLanguage, ::build)
            .stateIn(viewModelScope, WhileShown, sources.value?.let { build(it, TitleLanguage.Romaji) } ?: RelatedState.Loading(null))

    fun onEvent(event: RelatedEvent) {
        if (event == RelatedEvent.Retry) retry()
    }

    // What the screen shows of a related title, for its page.
    fun summaryOf(id: AnimeId): TitleSummary? {
        val sources = sources.value ?: return null
        if (sources.list.failure != null) return null
        val entry = sources.list.entries[id]
        // A related row knows less of a title than its list entry does.
        val anime =
            entry?.anime ?: sources.title.details
                ?.related
                ?.firstOrNull { it.anime.id == id }
                ?.anime ?: return null
        return TitleSummary(anime, entry, sources.airing[id], sources.title.session)
    }

    private fun build(
        sources: Sources,
        language: TitleLanguage,
    ): RelatedState {
        val title = sources.title
        val details = title.details
        val failure = title.failure
        val listFailure = sources.list.failure ?: title.storeFailure
        return when {
            listFailure != null -> RelatedState.Failed(listFailure, title.session)
            details != null ->
                relatedState(
                    details.related,
                    sources.list.entries.mapValues { it.value.values },
                    sources.airing,
                    language,
                    title.session,
                    clock.instant(),
                )
            failure != null -> RelatedState.Failed(failure, title.session)
            else -> RelatedState.Loading(title.session)
        }
    }
}

class DetailsViewModel(
    id: AnimeId,
    anime: AnimeRepository,
    session: SessionRepository,
    connection: Connection,
    titleLanguage: Flow<TitleLanguage>,
    private val clock: Clock,
    opened: TitleSummary? = null,
) : SubScreenViewModel(id, anime, session, connection, clock, opened) {
    val state: StateFlow<DetailsState> =
        combine(title, titleLanguage, ::build)
            .stateIn(viewModelScope, WhileShown, opened?.let { build(Title(it), TitleLanguage.Romaji) } ?: DetailsState.Loading(null))

    fun onEvent(event: DetailsEvent) {
        if (event == DetailsEvent.Retry) retry()
    }

    private fun build(
        title: Title,
        language: TitleLanguage,
    ): DetailsState {
        val anime = title.anime
        val details = title.details
        val failure = title.failure
        val storeFailure = title.storeFailure
        return when {
            storeFailure != null -> DetailsState.Failed(storeFailure, title.session)
            anime != null && details != null -> detailsState(anime, details, language, title.session, clock)
            failure != null -> DetailsState.Failed(failure, title.session)
            else -> DetailsState.Loading(title.session)
        }
    }
}

// One title's score distribution: asked once the screen is first shown, again only by a Retry after a failure.
class ScoresViewModel(
    private val id: AnimeId,
    private val scores: ScoreDistributionRepository,
    session: SessionRepository,
) : ViewModel() {
    // Null while it is asked.
    private val answer = MutableStateFlow<Outcome<ScoreDistribution?>?>(null)
    private var asked = false
    private var asking: Job? = null

    val state: StateFlow<ScoresState> =
        combine(
            answer.onStart {
                if (!asked) {
                    asked = true
                    ask()
                }
            },
            session.observeSession().onStart<Session?> { emit(null) },
        ) { answer, session -> build(answer, session) }
            .stateIn(viewModelScope, WhileShown, ScoresState.Loading(null))

    fun onEvent(event: ScoresEvent) {
        if (event == ScoresEvent.Retry && answer.value is Outcome.Failure) ask()
    }

    private fun ask() {
        if (asking?.isActive == true) return
        answer.value = null
        asking = viewModelScope.launch { answer.value = scores.distribution(id) }
    }

    private fun build(
        answer: Outcome<ScoreDistribution?>?,
        session: Session?,
    ): ScoresState =
        when (answer) {
            null -> ScoresState.Loading(session)
            is Outcome.Failure -> ScoresState.Failed(answer.error, session)
            is Outcome.Success -> answer.value?.let { ScoresState.Shown(it, session) } ?: ScoresState.NotKnown(session)
        }
}

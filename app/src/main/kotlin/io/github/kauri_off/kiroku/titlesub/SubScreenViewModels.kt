package io.github.kauri_off.kiroku.titlesub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import io.github.kauri_off.kiroku.core.repository.AiringRepository
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.title.TitleSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock

// What both sub-screens read: one title, its details, and whether loading them failed. Reading it first refreshes.
abstract class SubScreenViewModel(
    private val id: AnimeId,
    private val anime: AnimeRepository,
    session: SessionRepository,
) : ViewModel() {
    protected class Title(
        val anime: Anime?,
        val details: AnimeDetails?,
        val session: Session?,
        val failure: DataError?,
    ) {
        constructor(opened: TitleSummary) : this(opened.anime, opened.details, opened.session, null)
    }

    private val failure = MutableStateFlow<DataError?>(null)
    private var refreshed = false

    protected val title: Flow<Title> =
        combine(anime.observeAnime(id), anime.observeDetails(id), session.observeSession(), failure, ::Title)
            .onStart { if (!refreshed) refresh() }

    protected fun refresh() {
        refreshed = true
        failure.value = null
        viewModelScope.launch {
            val result = anime.refresh(id)
            if (result is Outcome.Failure) failure.value = result.error
        }
    }
}

class RelatedViewModel(
    id: AnimeId,
    anime: AnimeRepository,
    list: ListSource,
    airing: AiringRepository,
    session: SessionRepository,
    private val titleLanguage: TitleLanguage,
    opened: TitleSummary? = null,
) : SubScreenViewModel(id, anime, session) {
    private class ListRead(
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
        val failure: DataError? = null,
    )

    private class Sources(
        val title: Title,
        val list: ListRead,
        val airing: Map<AnimeId, Airing>,
    ) {
        constructor(opened: TitleSummary) : this(Title(opened), ListRead(opened.list), opened.airing)
    }

    private val retries = MutableStateFlow(0)

    // A list that cannot be read fails the screen, since the related titles' list statuses would be unknown.
    private val listRead =
        list
            .observeEntries()
            .map { list -> ListRead(list.associateBy { it.anime.id }) }
            .readAgainOnRetry(retries) { ListRead(failure = it) }

    private val sources: StateFlow<Sources?> =
        combine(title, listRead, airing.observeAiring(), ::Sources)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), opened?.let(::Sources))

    val state: StateFlow<RelatedState> =
        sources
            .filterNotNull()
            .map(::build)
            .stateIn(viewModelScope, WhileShown, sources.value?.let(::build) ?: RelatedState.Loading(null))

    fun onEvent(event: RelatedEvent) {
        if (event != RelatedEvent.Retry) return
        retries.update { it + 1 }
        refresh()
    }

    // What the screen shows of a related title, for its page.
    fun summaryOf(id: AnimeId): TitleSummary? {
        val sources = sources.value ?: return null
        val anime =
            sources.title.details
                ?.related
                ?.firstOrNull { it.anime.id == id }
                ?.anime ?: return null
        return TitleSummary(anime, null, sources.list.entries, sources.airing, sources.title.session)
    }

    private fun build(sources: Sources): RelatedState {
        val title = sources.title
        val details = title.details
        val failure = title.failure
        val listFailure = sources.list.failure
        return when {
            listFailure != null -> RelatedState.Failed(listFailure, title.session)
            details != null ->
                relatedState(
                    details.related,
                    sources.list.entries.mapValues { it.value.values },
                    sources.airing,
                    titleLanguage,
                    title.session,
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
    private val titleLanguage: TitleLanguage,
    private val clock: Clock,
    opened: TitleSummary? = null,
) : SubScreenViewModel(id, anime, session) {
    val state: StateFlow<DetailsState> =
        title
            .map(::build)
            .stateIn(viewModelScope, WhileShown, opened?.let { build(Title(it)) } ?: DetailsState.Loading(null))

    fun onEvent(event: DetailsEvent) {
        if (event == DetailsEvent.Retry) refresh()
    }

    private fun build(title: Title): DetailsState {
        val anime = title.anime
        val details = title.details
        val failure = title.failure
        return when {
            anime != null && details != null -> detailsState(anime, details, titleLanguage, title.session, clock)
            failure != null -> DetailsState.Failed(failure, title.session)
            else -> DetailsState.Loading(title.session)
        }
    }
}

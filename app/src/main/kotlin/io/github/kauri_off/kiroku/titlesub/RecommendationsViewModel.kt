package io.github.kauri_off.kiroku.titlesub

import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.AdultSearchChoice
import io.github.kauri_off.kiroku.core.repository.AnimeRepository
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.readAgainOnRetry
import io.github.kauri_off.kiroku.search.resultRow
import io.github.kauri_off.kiroku.title.TitleSummary
import io.github.kauri_off.kiroku.title.entriesOf
import io.github.kauri_off.kiroku.title.knownEntries
import io.github.kauri_off.kiroku.title.withRead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock

internal class RecommendationsViewModel(
    id: AnimeId,
    anime: AnimeRepository,
    list: ListSource,
    session: SessionRepository,
    connection: Connection,
    clock: Clock,
    titleLanguage: Flow<TitleLanguage>,
    opened: TitleSummary?,
    adultSearchChoice: Flow<AdultSearchChoice> = flowOf(AdultSearchChoice()),
    private val onRefreshFailed: (DataError) -> Unit,
) : SubScreenViewModel(id, anime, session, connection, clock, opened, adultSearchChoice) {
    private class ListRead(
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
        val failure: DataError? = null,
    )

    private class Sources(
        val title: Title,
        val list: ListRead,
    )

    private val titles = title.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), opened?.let(::Title))

    private val sources =
        titles
            .filterNotNull()
            .withRead({
                it.details
                    ?.recommendations
                    .orEmpty()
                    .map { anime -> anime.id }
                    .toSet()
            }) { ids ->
                list.entriesOf(ids).map { ListRead(it) }.readAgainOnRetry(retries) { ListRead(failure = it) }
            }.map { (title, list) -> Sources(title, list) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), opened?.let { Sources(Title(it), ListRead(it.knownEntries())) })

    private val pendingFailures = MutableStateFlow(emptyList<DataError>())
    private val presentation =
        combine(
            refreshing,
            titleLanguage,
            adultSearchChoice.onEach { choice ->
                if (recommendationFilter != null && recommendationFilter != choice) pendingFailures.value = emptyList()
                filterChanged(choice)
            },
            ::Triple,
        )

    val state =
        combine(sources, presentation, titles, pendingFailures) { source, (refreshing, language, choice), title, failures ->
            val adult = choice.enabled
            val state = build(source, refreshing, language, adult)
            if (failures.isNotEmpty()) {
                if (title?.details?.recommendationsFor(adult) != null && title.storeFailure == null) {
                    pendingFailures.getAndUpdate { emptyList() }.forEach(onRefreshFailed)
                } else if (state.content is RecommendationsContent.Failed) {
                    pendingFailures.value = emptyList()
                }
            }
            state
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
            build(sources.value, false, TitleLanguage.Romaji, false),
        )

    override fun refreshFailed(error: DataError) {
        pendingFailures.update { it + error }
    }

    fun onEvent(event: RecommendationsEvent) {
        if (event == RecommendationsEvent.Reload) retry()
    }

    fun summaryOf(id: AnimeId): TitleSummary? {
        val source = sources.value ?: return null
        val enabled = recommendationFilter?.enabled ?: false
        val anime =
            source.title.details
                ?.recommendationsFor(enabled)
                ?.firstOrNull { it.id == id } ?: return null
        return TitleSummary(anime, source.list.entries[id], airing = null, session = source.title.session)
    }

    private fun build(
        sources: Sources?,
        refreshing: Boolean,
        language: TitleLanguage,
        adult: Boolean,
    ): RecommendationsState {
        val title = sources?.title
        val session = title?.session
        val recommendations = title?.details?.recommendationsFor(adult)
        val error = title?.storeFailure ?: sources?.list?.failure?.takeIf { recommendations != null }
        val failure = title?.failure
        val content =
            when {
                error != null -> RecommendationsContent.Failed(error)
                recommendations != null ->
                    RecommendationsContent.Shown(
                        recommendations.distinctBy { it.id }.map {
                            resultRow(
                                it,
                                sources.list.entries[it.id]?.values,
                                language,
                            )
                        },
                        refreshing,
                    )
                failure != null -> RecommendationsContent.Failed(failure)
                else -> RecommendationsContent.Loading
            }
        return RecommendationsState(content, session)
    }
}

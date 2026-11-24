package io.github.kauri_off.kiroku.titlesub

import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.WhileShown
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.TitleLanguage
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
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
    private val titleLanguage: TitleLanguage,
    opened: TitleSummary?,
    private val onRefreshFailed: (DataError) -> Unit,
) : SubScreenViewModel(id, anime, session, connection, clock, opened) {
    private class ListRead(
        val entries: Map<AnimeId, ListEntry> = emptyMap(),
        val failure: DataError? = null,
    )

    private class Sources(
        val title: Title,
        val list: ListRead,
    ) {
        val recommendations: List<Anime> get() = title.details?.recommendations.orEmpty()
    }

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

    val state =
        combine(sources, refreshing, titles, pendingFailures) { source, refreshing, title, failures ->
            val state = build(source, refreshing)
            if (failures.isNotEmpty()) {
                if (title?.details != null && title.storeFailure == null) {
                    pendingFailures.getAndUpdate { emptyList() }.forEach(onRefreshFailed)
                } else if (state.content is RecommendationsContent.Failed) {
                    pendingFailures.value = emptyList()
                }
            }
            state
        }.stateIn(viewModelScope, WhileShown, build(sources.value, false))

    override fun refreshFailed(error: DataError) {
        pendingFailures.update { it + error }
    }

    fun onEvent(event: RecommendationsEvent) {
        if (event == RecommendationsEvent.Reload) retry()
    }

    fun summaryOf(id: AnimeId): TitleSummary? {
        val source = sources.value ?: return null
        val anime = source.recommendations.firstOrNull { it.id == id } ?: return null
        return TitleSummary(anime, source.list.entries[id], airing = null, session = source.title.session)
    }

    private fun build(
        sources: Sources?,
        refreshing: Boolean,
    ): RecommendationsState {
        val title = sources?.title
        val session = title?.session
        val error = title?.storeFailure ?: sources?.list?.failure
        val failure = title?.failure
        val content =
            when {
                error != null -> RecommendationsContent.Failed(error)
                title?.details != null ->
                    RecommendationsContent.Shown(
                        sources.recommendations.distinctBy { it.id }.map {
                            resultRow(
                                it,
                                sources.list.entries[it.id]?.values,
                                titleLanguage,
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

package io.github.kauri_off.kiroku.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.RefreshBox
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.StateAction
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.edit.MangaEditStatus
import io.github.kauri_off.kiroku.list.FilterResults
import io.github.kauri_off.kiroku.list.ListState
import io.github.kauri_off.kiroku.list.ListStatusPager
import io.github.kauri_off.kiroku.list.ListTabMotion
import io.github.kauri_off.kiroku.list.StatusTabs
import io.github.kauri_off.kiroku.list.belowBars
import io.github.kauri_off.kiroku.list.listScrollAnchor
import io.github.kauri_off.kiroku.list.listScrollSaver
import io.github.kauri_off.kiroku.list.rememberListTabMotion
import io.github.kauri_off.kiroku.title.countText
import io.github.kauri_off.kiroku.title.message
import io.github.kauri_off.kiroku.titlesub.SubScreenBody
import io.github.kauri_off.kiroku.titlesub.SubScreenList
import io.github.kauri_off.kiroku.titlesub.errorState

internal data class MangaListState(
    val owner: SignInId? = null,
    val content: MangaListContent = MangaListContent.Loading,
    val sessionExpired: Boolean = false,
    val tab: MangaReadingStatus = MangaEditStatus.Reading.readingStatus,
    val sorts: Map<MangaReadingStatus, TabSort> = emptyMap(),
    val sortTab: MangaReadingStatus? = null,
    val filter: ListState.Typed = ListState.Typed(null),
    val typed: ListState.Typed? = null,
    val matches: List<MangaListEntry>? = null,
) {
    val filterText get() = (typed ?: filter).text
    val activeFilterSession get() = (typed ?: filter).session

    fun sortFor(status: MangaReadingStatus): TabSort = sorts[status] ?: defaultMangaSort
}

internal sealed interface MangaListContent {
    data object Loading : MangaListContent

    data object SignedOut : MangaListContent

    data class Failed(
        val error: DataError,
    ) : MangaListContent

    data class Ready(
        val entries: List<MangaListEntry>,
        val language: TitleLanguage,
        val refreshing: Boolean = false,
        val canEdit: Boolean = false,
        val confirmedStatuses: Set<MangaReadingStatus> = emptySet(),
    ) : MangaListContent {
        val pages: Map<MangaReadingStatus, List<MangaListEntry>> = entries.groupBy(MangaListEntry::status)
        val statuses: List<MangaReadingStatus> =
            knownMangaStatuses + ((pages.keys + confirmedStatuses) - knownMangaStatuses.toSet()).sortedBy(MangaReadingStatus::code)

        fun pageIndex(tab: MangaReadingStatus): Int = statuses.indexOf(tab).coerceAtLeast(0)
    }
}

internal sealed interface MangaListEvent {
    data object SignIn : MangaListEvent

    data object Refresh : MangaListEvent

    data object Retry : MangaListEvent

    data object StartFilter : MangaListEvent

    data object EndFilter : MangaListEvent

    data class FilterChanged(
        val text: String,
    ) : MangaListEvent

    data class OpenSort(
        val tab: MangaReadingStatus,
    ) : MangaListEvent

    data object DismissSort : MangaListEvent

    data class SelectSort(
        val sort: ListSort,
    ) : MangaListEvent

    data class SelectDirection(
        val direction: SortDirection,
    ) : MangaListEvent

    data class SelectTab(
        val status: MangaReadingStatus,
    ) : MangaListEvent

    data class Edit(
        val entry: MangaListEntry,
    ) : MangaListEvent

    data class Open(
        val entry: MangaListEntry,
    ) : MangaListEvent
}

private val knownMangaStatuses = MangaEditStatus.entries.map(MangaEditStatus::readingStatus)

@Composable
internal fun rememberMangaListMotion(
    content: MangaListContent.Ready,
    tab: MangaReadingStatus,
): ListTabMotion {
    val statuses = content.statuses
    val pager = remember(statuses) { PagerState(currentPage = content.pageIndex(tab)) { statuses.size } }
    return rememberListTabMotion(pager)
}

@Composable
internal fun MangaListTabs(
    motion: ListTabMotion,
    statuses: List<MangaReadingStatus>,
) = StatusTabs(
    motion,
    statuses.size,
    label = { statuses[it].displayName() },
    onSelect = {},
)

@Composable
internal fun MangaListBody(
    state: MangaListState,
    onEvent: (MangaListEvent) -> Unit,
    motion: ListTabMotion?,
    padding: PaddingValues,
) {
    val refresh = { onEvent(MangaListEvent.Refresh) }
    val body = Modifier.padding(padding)
    when (val content = state.content) {
        MangaListContent.Loading -> ScreenState(ScreenState.Loading, body)
        MangaListContent.SignedOut ->
            ScreenState(
                ScreenState.Empty(
                    stringResource(R.string.manga_list_signed_out),
                    StateAction(stringResource(R.string.list_sign_in)) { onEvent(MangaListEvent.SignIn) },
                ),
                body,
            )
        is MangaListContent.Failed ->
            ScreenState(
                if (state.sessionExpired) {
                    ScreenState.Empty(stringResource(content.error.message()))
                } else {
                    errorState(content.error) { onEvent(MangaListEvent.Retry) }
                },
                body,
            )
        is MangaListContent.Ready -> {
            val direction = LocalLayoutDirection.current
            val pagePadding =
                PaddingValues(
                    start = padding.calculateStartPadding(direction),
                    end = padding.calculateEndPadding(direction),
                    bottom = padding.calculateBottomPadding(),
                )
            val scrolls =
                content.statuses.associateWith { status ->
                    val rows = content.pages[status].orEmpty()
                    val choice = state.sortFor(status)
                    key(status.code, choice.sort.name, choice.direction?.name, content.language.name) {
                        rememberMangaScroll(rows)
                    }
                }
            val pages: @Composable () -> Unit = {
                if (motion == null) {
                    val matches = state.matches.takeIf { state.filter.session == state.activeFilterSession }
                    key(state.activeFilterSession, state.filter.text) {
                        val scroll = rememberMangaScroll(matches.orEmpty())
                        FilterResults(matches?.isNotEmpty(), pagePadding) { bottom ->
                            val rows = checkNotNull(matches)
                            MangaRows(
                                rows,
                                content,
                                scroll,
                                onEvent,
                                PaddingValues(
                                    start = pagePadding.calculateStartPadding(direction),
                                    end = pagePadding.calculateEndPadding(direction),
                                    bottom = bottom.calculateBottomPadding(),
                                ),
                                showStatus = true,
                            )
                        }
                    }
                } else {
                    MangaPages(state.tab, content, motion, scrolls, onEvent, pagePadding)
                }
            }
            val pagerBody = Modifier.belowBars(padding).fillMaxSize()
            if (state.sessionExpired) {
                Box(pagerBody) { pages() }
            } else {
                RefreshBox(content.refreshing, refresh, pagerBody) { pages() }
            }
        }
    }
}

private class MangaViewport(
    var saved: List<Long> = emptyList(),
) {
    var visible: LazyListState? = null

    fun anchor(): List<Long> = visible?.listScrollAnchor()?.takeIf { it.size == 3 } ?: saved
}

private val mangaViewportSaver =
    listSaver<MangaViewport, Long>(save = { it.anchor() }, restore = ::MangaViewport)

@Composable
private fun rememberMangaScroll(rows: List<MangaListEntry>): LazyListState? {
    val viewport = rememberSaveable(saver = mangaViewportSaver) { MangaViewport() }
    if (rows.isEmpty()) return null
    val scroll =
        remember(viewport) {
            viewport.saved.takeIf { it.isNotEmpty() }?.let { anchor ->
                listScrollSaver(rows.lastIndex) { id -> rows.indexOfFirst { it.id.value == id } }.restore(anchor)
            } ?: LazyListState()
        }
    DisposableEffect(viewport, scroll) {
        viewport.visible = scroll
        onDispose {
            viewport.saved = viewport.anchor()
            viewport.visible = null
        }
    }
    return scroll
}

@Composable
private fun MangaPages(
    tab: MangaReadingStatus,
    content: MangaListContent.Ready,
    motion: ListTabMotion,
    scrolls: Map<MangaReadingStatus, LazyListState?>,
    onEvent: (MangaListEvent) -> Unit,
    padding: PaddingValues,
) {
    val index = content.pageIndex(tab)
    val selection = rememberUpdatedState(tab)
    val events = rememberUpdatedState(onEvent)
    LaunchedEffect(motion) {
        motion.followRest { page ->
            val status = content.statuses[page]
            if (status != selection.value) events.value(MangaListEvent.SelectTab(status))
        }
    }
    LaunchedEffect(motion, index) { motion.show(index) }
    key(motion) {
        ListStatusPager(motion, key = { content.statuses[it].code }) { page ->
            val status = content.statuses[page]
            MangaListPage(content, status, scrolls.getValue(status), onEvent, padding)
        }
    }
}

@Composable
private fun MangaListPage(
    content: MangaListContent.Ready,
    status: MangaReadingStatus,
    scroll: LazyListState?,
    onEvent: (MangaListEvent) -> Unit,
    padding: PaddingValues,
) {
    val entries = content.pages[status].orEmpty()
    val empty =
        if (entries.isNotEmpty()) {
            null
        } else if (content.entries.isEmpty()) {
            stringResource(R.string.manga_list_empty)
        } else {
            stringResource(R.string.list_empty, status.displayName())
        }
    MangaRows(entries, content, scroll, onEvent, padding, empty)
}

@Composable
private fun MangaRows(
    entries: List<MangaListEntry>,
    content: MangaListContent.Ready,
    scroll: LazyListState?,
    onEvent: (MangaListEvent) -> Unit,
    padding: PaddingValues,
    empty: String? = null,
    showStatus: Boolean = false,
) {
    SubScreenList(
        SubScreenBody.List(scroll = scroll) {
            if (empty != null) {
                item {
                    ScreenState(ScreenState.Empty(empty), Modifier.fillParentMaxSize().padding(padding))
                }
            } else {
                items(entries, key = { it.id.value }) { entry ->
                    MangaListRow(
                        entry,
                        content.language,
                        showStatus,
                        open = { onEvent(MangaListEvent.Open(entry)) },
                        edit = if (content.canEdit) ({ onEvent(MangaListEvent.Edit(entry)) }) else null,
                    )
                }
            }
        },
        padding,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaListRow(
    entry: MangaListEntry,
    language: TitleLanguage,
    showStatus: Boolean,
    open: () -> Unit,
    edit: (() -> Unit)?,
) {
    val progress = entry.progressParts()
    ListItem(
        onClick = open,
        onLongClick = edit,
        onLongClickLabel = edit?.let { stringResource(R.string.list_edit) },
        leadingContent = { Poster(entry.posterUrl, PosterSize.Row) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        overlineContent = if (showStatus) ({ Text(entry.status.displayName()) }) else null,
        supportingContent =
            if (progress.isNotEmpty()) {
                { Text(progress.joinToString(stringResource(R.string.common_separator))) }
            } else {
                null
            },
        content = {
            TitleText(entry.titles.preferred(language), maxLines = Int.MAX_VALUE, style = MaterialTheme.typography.titleMedium)
        },
    )
}

@Composable
internal fun MangaReadingStatus.displayName(): String =
    MangaEditStatus.entries.find { it.readingStatus == this }?.let { stringResource(it.label) } ?: malCodeAsText(code).ifBlank { code }

@Composable
internal fun MangaListEntry.progressParts(): List<String> {
    val chapters = chaptersRead?.let { pluralStringResource(R.plurals.manga_chapters, it, countText(it)) }
    val volumes = volumesRead?.let { pluralStringResource(R.plurals.manga_volumes, it, countText(it)) }
    val scoreLabel = score?.let { stringResource(R.string.title_your_score_spoken, it) }
    return listOfNotNull(chapters, volumes, scoreLabel)
}

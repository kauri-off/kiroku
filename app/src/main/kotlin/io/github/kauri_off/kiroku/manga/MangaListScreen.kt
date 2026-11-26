package io.github.kauri_off.kiroku.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.StateAction
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.list.ListFrame
import io.github.kauri_off.kiroku.list.ListMode
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.title.countText
import io.github.kauri_off.kiroku.title.message
import io.github.kauri_off.kiroku.titlesub.SubScreenBody
import io.github.kauri_off.kiroku.titlesub.SubScreenContent
import io.github.kauri_off.kiroku.titlesub.errorState

internal data class MangaListState(
    val owner: SignInId? = null,
    val content: MangaListContent = MangaListContent.Loading,
    val sessionExpired: Boolean = false,
)

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
    ) : MangaListContent
}

internal sealed interface MangaListEvent {
    data object SignIn : MangaListEvent

    data object Refresh : MangaListEvent

    data class Open(
        val entry: MangaListEntry,
    ) : MangaListEvent
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaListScreen(
    state: MangaListState,
    onEvent: (MangaListEvent) -> Unit,
    snackbars: SnackbarHostState,
    onMode: (ListMode) -> Unit,
) {
    val refresh = { onEvent(MangaListEvent.Refresh) }
    val scroll = rememberLazyListState()
    ListFrame(
        ListMode.Manga,
        onMode,
        state.sessionExpired,
        onSignIn = { onEvent(MangaListEvent.SignIn) },
        snackbars,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.list_title)) }) },
    ) { padding ->
        val body =
            when (val content = state.content) {
                MangaListContent.Loading -> SubScreenBody.State(ScreenState.Loading)
                MangaListContent.SignedOut ->
                    SubScreenBody.State(
                        ScreenState.Empty(
                            stringResource(R.string.manga_list_signed_out),
                            StateAction(stringResource(R.string.list_sign_in)) { onEvent(MangaListEvent.SignIn) },
                        ),
                    )
                is MangaListContent.Failed ->
                    SubScreenBody.State(
                        if (state.sessionExpired) {
                            ScreenState.Empty(stringResource(content.error.message()))
                        } else {
                            errorState(content.error, refresh)
                        },
                    )
                is MangaListContent.Ready ->
                    SubScreenBody.List(content.refreshing, onRefresh = refresh.takeUnless { state.sessionExpired }, scroll = scroll) {
                        if (content.entries.isEmpty()) {
                            item {
                                Box(Modifier.fillParentMaxSize()) {
                                    ScreenState(ScreenState.Empty(stringResource(R.string.manga_list_empty)))
                                }
                            }
                        } else {
                            items(content.entries, key = { it.id.value }) { entry ->
                                MangaListRow(entry, content.language) { onEvent(MangaListEvent.Open(entry)) }
                            }
                        }
                    }
            }
        SubScreenContent(body, padding)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MangaListRow(
    entry: MangaListEntry,
    language: TitleLanguage,
    open: () -> Unit,
) {
    ListItem(
        onClick = open,
        leadingContent = { Poster(entry.posterUrl, PosterSize.Row) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        supportingContent = {
            val status =
                when (entry.status.code) {
                    notShown("reading") -> stringResource(R.string.manga_list_reading)
                    notShown("completed") -> stringResource(R.string.manga_list_completed)
                    notShown("on_hold") -> stringResource(R.string.manga_list_on_hold)
                    notShown("dropped") -> stringResource(R.string.manga_list_dropped)
                    notShown("plan_to_read") -> stringResource(R.string.manga_list_planned)
                    else -> malCodeAsText(entry.status.code)
                }
            val chapters = entry.chaptersRead?.let { pluralStringResource(R.plurals.manga_chapters, it, countText(it)) }
            val volumes = entry.volumesRead?.let { pluralStringResource(R.plurals.manga_volumes, it, countText(it)) }
            val score = entry.score?.let { stringResource(R.string.title_your_score_spoken, it) }
            Text(listOfNotNull(status, chapters, volumes, score).joinToString(stringResource(R.string.common_separator)))
        },
        content = {
            TitleText(entry.titles.preferred(language), maxLines = Int.MAX_VALUE, style = MaterialTheme.typography.titleMedium)
        },
    )
}

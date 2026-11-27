package io.github.kauri_off.kiroku.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaPublication
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.component.TitleSelection
import io.github.kauri_off.kiroku.design.component.TitleText
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.title.MalScore
import io.github.kauri_off.kiroku.title.PublicTitleStats
import io.github.kauri_off.kiroku.title.TitleAddButton
import io.github.kauri_off.kiroku.title.TitleCover
import io.github.kauri_off.kiroku.title.TitleCoverDialog
import io.github.kauri_off.kiroku.title.TitleStatusButton
import io.github.kauri_off.kiroku.title.countText
import io.github.kauri_off.kiroku.title.hasTitleCover
import io.github.kauri_off.kiroku.title.message
import io.github.kauri_off.kiroku.titlesub.SubScreen
import io.github.kauri_off.kiroku.titlesub.SubScreenBody

internal data class MangaTitleState(
    val content: MangaTitleContent = MangaTitleContent.Loading,
    val sessionExpired: Boolean = false,
    val listEntry: MangaListEntry? = null,
    val membershipError: DataError? = null,
    val addAction: MangaAddAction? = null,
    val reconcilingAdd: Boolean = false,
    val checkingMembership: Boolean = false,
    val coverOpen: Boolean = false,
)

internal enum class MangaAddAction { Available, Saving }

internal sealed interface MangaTitleContent {
    data object Loading : MangaTitleContent

    data class Failed(
        val problem: MangaTitleProblem,
    ) : MangaTitleContent

    data class Shown(
        val details: MangaDetails,
        val title: String,
        val refreshing: Boolean = false,
        val coverAttempt: Int = 0,
        val detailsError: DataError? = null,
        val detailsRead: Boolean = false,
    ) : MangaTitleContent
}

internal sealed interface MangaTitleProblem {
    data object Unavailable : MangaTitleProblem

    data class ReadFailed(
        val error: DataError,
    ) : MangaTitleProblem
}

internal enum class MangaTitleEvent { Back, Retry, Refresh, SignIn, Edit, AddToList, RetryMembership, OpenCover, CloseCover }

@Composable
internal fun MangaTitleScreen(
    state: MangaTitleState,
    onEvent: (MangaTitleEvent) -> Unit,
    snackbars: SnackbarHostState,
) {
    val content = state.content
    SubScreen(
        title = null,
        sessionExpired = state.sessionExpired,
        onBack = { onEvent(MangaTitleEvent.Back) },
        onSignIn = { onEvent(MangaTitleEvent.SignIn) },
        snackbars = snackbars,
        body =
            when (content) {
                MangaTitleContent.Loading -> SubScreenBody.State(ScreenState.Loading)
                is MangaTitleContent.Failed ->
                    SubScreenBody.State(
                        when (val problem = content.problem) {
                            MangaTitleProblem.Unavailable -> ScreenState.Empty(stringResource(R.string.manga_unavailable))
                            is MangaTitleProblem.ReadFailed ->
                                ScreenState.Error(stringResource(problem.error.message()), onRetry = { onEvent(MangaTitleEvent.Retry) })
                        },
                    )
                is MangaTitleContent.Shown ->
                    SubScreenBody.List(content.refreshing, onRefresh = { onEvent(MangaTitleEvent.Refresh) }) {
                        item { MangaHeader(content) { onEvent(MangaTitleEvent.OpenCover) } }
                        state.listEntry?.let { entry ->
                            item { MangaOnList(entry) { onEvent(MangaTitleEvent.Edit) } }
                        }
                        state.addAction?.let { action ->
                            item {
                                TitleAddButton(
                                    onClick = { onEvent(MangaTitleEvent.AddToList) },
                                    modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.xl).fillMaxWidth(),
                                    enabled = action == MangaAddAction.Available,
                                )
                            }
                        }
                        if (state.reconcilingAdd || state.membershipError != null) {
                            item {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stringResource(
                                            if (state.reconcilingAdd) {
                                                R.string.manga_edit_unconfirmed
                                            } else {
                                                R.string.manga_edit_membership_failed
                                            },
                                        ),
                                        modifier = Modifier.padding(horizontal = Spacing.l),
                                        textAlign = TextAlign.Center,
                                    )
                                    TextButton(
                                        onClick = { onEvent(MangaTitleEvent.RetryMembership) },
                                        enabled = !state.checkingMembership,
                                    ) {
                                        Text(stringResource(R.string.manga_edit_refresh_list))
                                    }
                                }
                            }
                        }
                        content.detailsError?.let { error ->
                            item {
                                ScreenState(ScreenState.Error(stringResource(error.message())) { onEvent(MangaTitleEvent.Retry) })
                            }
                        }
                        content.details.synopsis?.let { synopsis ->
                            item {
                                Column(Modifier.padding(top = Spacing.l)) {
                                    SectionHeader(stringResource(R.string.title_synopsis))
                                    SelectionContainer {
                                        Text(
                                            synopsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(horizontal = Spacing.l),
                                        )
                                    }
                                }
                            }
                        }
                    }
            },
    )
    if (content is MangaTitleContent.Shown && state.coverOpen) {
        val details = content.details
        TitleCoverDialog(content.title, details.posterUrl, details.largePosterUrl) { onEvent(MangaTitleEvent.CloseCover) }
    }
}

@Composable
private fun MangaHeader(
    content: MangaTitleContent.Shown,
    onOpenCover: () -> Unit,
) {
    val details = content.details
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (hasTitleCover(details.posterUrl, details.largePosterUrl)) {
            key(content.coverAttempt) { TitleCover(details.posterUrl, details.largePosterUrl, onOpenCover) }
        }
        TitleSelection {
            TitleText(
                content.title,
                maxLines = Int.MAX_VALUE,
                style =
                    MaterialTheme.typography.headlineSmallEmphasized.copy(
                        lineBreak = LineBreak.Heading,
                        textAlign = TextAlign.Center,
                    ),
                modifier = Modifier.semantics { heading() },
            )
        }
        val facts =
            listOfNotNull(
                details.format?.label(),
                details.publication?.label(),
                details.volumes?.let { pluralStringResource(R.plurals.manga_volumes, it, countText(it)) },
                details.chapters?.let { pluralStringResource(R.plurals.manga_chapters, it, countText(it)) },
            )
        if (facts.isNotEmpty()) {
            Text(
                facts.joinToString(stringResource(R.string.common_separator)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        val score = details.meanScore?.let(MalScore::Given) ?: if (content.detailsRead) MalScore.None else MalScore.NotKnownYet
        PublicTitleStats(score, details.rank, details.members)
    }
}

@Composable
private fun MangaOnList(
    entry: MangaListEntry,
    onEdit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = Spacing.l, end = Spacing.l, top = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        TitleStatusButton(entry.status.displayName(), onEdit, Modifier.fillMaxWidth())
        val progress = entry.progressParts()
        val shown = if (entry.explicitlyUnrated) progress + stringResource(R.string.edit_score_0) else progress
        if (shown.isNotEmpty()) {
            Text(
                shown.joinToString(stringResource(R.string.common_separator)),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun MangaFormat.label(): String =
    when (this) {
        MangaFormat.Known.Manga -> stringResource(R.string.manga_format_manga)
        MangaFormat.Known.Novel -> stringResource(R.string.manga_format_novel)
        MangaFormat.Known.LightNovel -> stringResource(R.string.manga_format_light_novel)
        MangaFormat.Known.OneShot -> stringResource(R.string.manga_format_one_shot)
        MangaFormat.Known.Doujinshi -> stringResource(R.string.manga_format_doujinshi)
        MangaFormat.Known.Manhwa -> stringResource(R.string.manga_format_manhwa)
        MangaFormat.Known.Manhua -> stringResource(R.string.manga_format_manhua)
        MangaFormat.Known.Oel -> stringResource(R.string.manga_format_oel)
        is MangaFormat.Unrecognized -> malCodeAsText(code)
    }

@Composable
private fun MangaPublication.label(): String =
    when (this) {
        MangaPublication.Known.Finished -> stringResource(R.string.manga_publication_finished)
        MangaPublication.Known.Publishing -> stringResource(R.string.manga_publication_publishing)
        MangaPublication.Known.Upcoming -> stringResource(R.string.manga_publication_upcoming)
        MangaPublication.Known.OnHiatus -> stringResource(R.string.manga_publication_on_hiatus)
        MangaPublication.Known.Discontinued -> stringResource(R.string.manga_publication_discontinued)
        is MangaPublication.Unrecognized -> malCodeAsText(code)
    }

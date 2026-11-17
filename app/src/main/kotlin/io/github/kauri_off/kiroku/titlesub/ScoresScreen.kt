package io.github.kauri_off.kiroku.titlesub

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.score.ScoreDistribution
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.StatBar
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notShown
import java.text.NumberFormat

// MAL's votes per score: the votes in all, then one bar per score, 10 first, as the title page's statistics bars.
@Composable
fun ScoresScreen(
    state: ScoresState,
    onEvent: (ScoresEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    val body =
        when (state) {
            is ScoresState.Loading -> SubScreenBody.State(ScreenState.Loading)
            is ScoresState.NotKnown -> SubScreenBody.State(ScreenState.Empty(stringResource(R.string.titlesub_scores_not_known)))
            is ScoresState.Failed ->
                SubScreenBody.State(ScreenState.Error(stringResource(state.error.scoresMessage())) { onEvent(ScoresEvent.Retry) })
            is ScoresState.Shown -> {
                val distribution = state.distribution
                if (distribution.total == 0) {
                    SubScreenBody.State(ScreenState.Empty(stringResource(R.string.titlesub_scores_none)))
                } else {
                    shown(distribution)
                }
            }
        }
    SubScreen(
        title = stringResource(R.string.title_scores),
        sessionExpired = state.sessionExpired,
        onBack = { onEvent(ScoresEvent.Back) },
        onSignIn = { onEvent(ScoresEvent.SignIn) },
        snackbars = snackbars,
        body = body,
        modifier = modifier,
    )
}

@Composable
private fun shown(distribution: ScoreDistribution): SubScreenBody {
    val numbers = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val total = pluralStringResource(R.plurals.titlesub_scores_votes, distribution.total, numbers.format(distribution.total))
    val labels = ScoreDistribution.SCORES.associateWith { numbers.format(it) }
    val spoken = ScoreDistribution.SCORES.associateWith { stringResource(R.string.titlesub_scores_spoken, it) }
    return SubScreenBody.List {
        item(key = TOTAL) {
            Text(
                total,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
            )
        }
        items(ScoreDistribution.SCORES.reversed().toList(), key = { it }) { score ->
            StatBar(labels.getValue(score), distribution[score], distribution.total, spokenLabel = spoken.getValue(score))
        }
    }
}

// Worded for this service alone: MAL's wording of a refusal or a missing title is not this screen's (rule 4 of the audit).
private fun DataError.scoresMessage(): Int =
    if (this == DataError.NoConnection) R.string.common_no_connection else R.string.titlesub_scores_failed

private val TOTAL = notShown("total")

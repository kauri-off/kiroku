package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.design.theme.isLargeText
import io.github.kauri_off.kiroku.notShown

// A supporting line of a row; a highlighted line is the "to watch" figure, in the tertiary colour.
data class RowLine(
    val text: String,
    val highlighted: Boolean = false,
)

// The one trailing element a row may have: the MAL score, or the "+1" button.
sealed interface RowTrailing {
    // [text] is the score as written; a star says what it is, and screen readers hear "MAL score" with it.
    data class MalScore(
        val text: String,
    ) : RowTrailing

    // Prominent while the row has episodes to watch, quiet once the user has caught up; same size and place.
    data class PlusOne(
        val prominent: Boolean = true,
        val onClick: () -> Unit,
    ) : RowTrailing
}

// What a long press on a row does; screen readers get it as the row's long-click action, named [label].
data class RowLongPress(
    val label: String,
    val onLongPress: () -> Unit,
)

// Poster, a title of at most two lines, up to two supporting lines and one optional trailing element. A row with "+1"
// starts its text level with the poster's top, so the button stays in one place; any other row centres its text.
@Composable
fun AnimeRow(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lines: List<RowLine> = emptyList(),
    trailing: RowTrailing? = null,
    longPress: RowLongPress? = null,
) {
    require(lines.size <= MAX_SUPPORTING_LINES)
    // With large text the score leaves the trailing slot for the end of the first line, so the title keeps its width.
    val scoreBelow = (trailing as? RowTrailing.MalScore)?.takeIf { isLargeText() }
    ListItem(
        onClick = onClick,
        modifier = modifier,
        leadingContent = { Poster(posterUrl, PosterSize.Row) },
        trailingContent = trailing?.takeIf { scoreBelow == null }?.let { { Trailing(it) } },
        supportingContent =
            if (scoreBelow != null) {
                {
                    Column {
                        ScoredLine(lines.firstOrNull(), scoreBelow)
                        lines.drop(1).forEach { SupportingLine(it) }
                    }
                }
            } else {
                lines.takeIf { it.isNotEmpty() }?.let { { Column { it.forEach { line -> SupportingLine(line) } } } }
            },
        verticalAlignment = if (trailing is RowTrailing.PlusOne) Alignment.Top else Alignment.CenterVertically,
        onLongClick = longPress?.onLongPress,
        onLongClickLabel = longPress?.label,
        content = { TitleText(title, maxLines = 2) },
    )
}

const val MAX_SUPPORTING_LINES = 2

@Composable
private fun SupportingLine(line: RowLine) {
    if (line.highlighted) {
        Text(line.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    } else {
        Text(line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Trailing(trailing: RowTrailing) {
    when (trailing) {
        is RowTrailing.MalScore -> MalScore(trailing)
        is RowTrailing.PlusOne -> PlusOne(trailing)
    }
}

// In a caption role, centred with the row's text; the star is as tall as the caption's line.
@Composable
private fun MalScore(trailing: RowTrailing.MalScore) {
    val style = MaterialTheme.typography.labelLarge
    val spoken = stringResource(R.string.design_mal_score, trailing.text)
    val star = with(LocalDensity.current) { style.lineHeight.toDp() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
    ) {
        Icon(painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.size(star))
        Text(trailing.text, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// A supporting line with the star and the score after it; the star never parts from its number.
@Composable
private fun ScoredLine(
    line: RowLine?,
    score: RowTrailing.MalScore,
) {
    val separator = stringResource(R.string.common_separator)
    val spoken = listOfNotNull(line?.text, stringResource(R.string.design_mal_score, score.text)).joinToString(separator)
    val text =
        buildAnnotatedString {
            line?.let {
                append(it.text)
                append(separator)
            }
            appendInlineContent(STAR)
            append(NO_BREAK_SPACE)
            append(score.text)
        }
    val star =
        InlineTextContent(Placeholder(1.em, 1.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(painterResource(R.drawable.ic_star), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    Text(
        text,
        inlineContent = mapOf(STAR to star),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clearAndSetSemantics { this.text = AnnotatedString(spoken) },
    )
}

private val STAR = notShown("star")
private const val NO_BREAK_SPACE = '\u00A0'

// The button's visible edge, not its touch target, ends on the content edge.
@Composable
private fun PlusOne(trailing: RowTrailing.PlusOne) {
    val icon = @Composable {
        Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.design_episode_plus))
    }
    val place = Modifier.offset(x = Spacing.xs)
    TrailingControl {
        if (trailing.prominent) {
            FilledTonalIconButton(onClick = trailing.onClick, shapes = IconButtonDefaults.shapes(), modifier = place, content = icon)
        } else {
            IconButton(
                onClick = trailing.onClick,
                modifier = place,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                content = icon,
            )
        }
    }
}

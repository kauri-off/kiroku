package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing

// A supporting line of a row; a highlighted line is the "to watch" figure, in the tertiary colour.
data class RowLine(
    val text: String,
    val highlighted: Boolean = false,
)

// The one trailing element a row may have: a short value such as the MAL score, or the "+1" button.
sealed interface RowTrailing {
    data class Value(
        val text: String,
    ) : RowTrailing

    data class PlusOne(
        val onClick: () -> Unit,
    ) : RowTrailing
}

// Poster, a title of at most two lines, up to two supporting lines and one optional trailing element.
@Composable
fun AnimeRow(
    title: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lines: List<RowLine> = emptyList(),
    trailing: RowTrailing? = null,
) {
    require(lines.size <= MAX_SUPPORTING_LINES)
    ListItem(
        headlineContent = { Text(shownTitle(title), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent =
            lines.takeIf { it.isNotEmpty() }?.let {
                {
                    // Two lines' room keeps every row three-line, so text and trailing element sit alike in each.
                    val twoLines = with(LocalDensity.current) { (MaterialTheme.typography.bodyMedium.lineHeight * 2).toDp() }
                    Column(Modifier.heightIn(min = twoLines)) {
                        it.forEach { line -> SupportingLine(line) }
                    }
                }
            },
        leadingContent = { Poster(posterUrl, PosterSize.Row) },
        trailingContent = trailing?.let { { Trailing(it) } },
        modifier = modifier.clickable(onClick = onClick),
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

// A value sits centred on the title's first line, in a caption role. The button's visible edge, not its touch
// target, ends on the content edge.
@Composable
private fun Trailing(trailing: RowTrailing) {
    when (trailing) {
        is RowTrailing.Value -> {
            val line =
                with(LocalDensity.current) {
                    MaterialTheme.typography.bodyLarge.lineHeight
                        .toDp()
                }
            Box(Modifier.heightIn(min = line), contentAlignment = Alignment.Center) {
                Text(trailing.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is RowTrailing.PlusOne ->
            FilledTonalIconButton(onClick = trailing.onClick, modifier = Modifier.offset(x = Spacing.xs)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.design_episode_plus))
            }
    }
}

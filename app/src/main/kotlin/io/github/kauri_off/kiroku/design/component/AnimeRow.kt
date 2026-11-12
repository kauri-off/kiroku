package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
    ListItem(
        onClick = onClick,
        modifier = modifier,
        leadingContent = { Poster(posterUrl, PosterSize.Row) },
        trailingContent = trailing?.let { { Trailing(it) } },
        supportingContent = lines.takeIf { it.isNotEmpty() }?.let { { Column { it.forEach { line -> SupportingLine(line) } } } },
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

// A value, in a caption role, is centred with the row's text.
@Composable
private fun Trailing(trailing: RowTrailing) {
    when (trailing) {
        is RowTrailing.Value ->
            Text(trailing.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is RowTrailing.PlusOne -> PlusOne(trailing)
    }
}

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

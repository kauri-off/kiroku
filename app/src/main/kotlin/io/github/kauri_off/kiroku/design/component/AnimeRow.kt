package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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

// What a long press on a row does, also offered to accessibility services as a custom action named [label].
data class RowLongPress(
    val label: String,
    val onLongPress: () -> Unit,
)

// Poster, a title of at most two lines, up to two supporting lines and one optional trailing element.
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
    val haptics = LocalHapticFeedback.current
    ListItem(
        headlineContent = { TitleText(title, maxLines = 2) },
        supportingContent =
            lines.takeIf { it.isNotEmpty() }?.let {
                {
                    // Reported as two lines, every row is three-line, so text and trailing element sit alike in each.
                    val line =
                        with(LocalDensity.current) {
                            MaterialTheme.typography.bodyMedium.lineHeight
                                .toDp()
                        }
                    Column(Modifier.asTwoLines(line)) {
                        it.forEach { line -> SupportingLine(line) }
                    }
                }
            },
        leadingContent = { Poster(posterUrl, PosterSize.Row) },
        trailingContent = trailing?.let { { Trailing(it) } },
        modifier =
            if (longPress == null) {
                modifier.clickable(onClick = onClick)
            } else {
                modifier
                    .combinedClickable(
                        onClick = onClick,
                        onLongClickLabel = longPress.label,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            longPress.onLongPress()
                        },
                    ).semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(longPress.label) {
                                    longPress.onLongPress()
                                    true
                                },
                            )
                    }
            },
    )
}

const val MAX_SUPPORTING_LINES = 2

// ListItem picks its layout from the supporting text's first and last baselines, so one line reports a second.
private fun Modifier.asTwoLines(line: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val first = placeable[FirstBaseline]
        val lines =
            if (first == AlignmentLine.Unspecified) {
                emptyMap()
            } else {
                mapOf<AlignmentLine, Int>(LastBaseline to maxOf(placeable[LastBaseline], first + line.roundToPx()))
            }
        layout(placeable.width, placeable.height, lines) { placeable.place(0, 0) }
    }

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
        is RowTrailing.PlusOne -> {
            val icon = @Composable {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.design_episode_plus))
            }
            val place = Modifier.offset(x = Spacing.xs)
            if (trailing.prominent) {
                FilledTonalIconButton(onClick = trailing.onClick, modifier = place, content = icon)
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
}

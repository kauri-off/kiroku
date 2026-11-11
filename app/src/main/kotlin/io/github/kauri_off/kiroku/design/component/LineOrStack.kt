package io.github.kauri_off.kiroku.design.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

// A text and an optional element at the end of its line. When both do not fit, the element moves under the text and
// stays at the end, so the text never breaks inside a word to make room. [stackedEndInset] keeps a stacked text
// clear of the end edge; [centred] centres both in the height given.
@Composable
internal fun LineOrStack(
    gap: Dp,
    modifier: Modifier = Modifier,
    stackedEndInset: Dp = gap,
    centred: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val end = measurables.getOrNull(1)?.measure(loose)
        val endWidth = end?.width ?: 0
        val gapPx = if (end == null) 0 else gap.roundToPx()
        val textWidth = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val bounded = constraints.hasBoundedWidth
        val inline = end == null || !bounded || textWidth + gapPx + endWidth <= constraints.maxWidth
        val width = if (bounded) constraints.maxWidth else textWidth + gapPx + endWidth
        val textSpace = if (inline) width - gapPx - endWidth else width - stackedEndInset.roundToPx()
        val text = measurables[0].measure(Constraints(maxWidth = textSpace.coerceAtLeast(0)))
        val endHeight = end?.height ?: 0
        if (inline) {
            val height = maxOf(text.height, endHeight, constraints.minHeight)
            layout(width, height) {
                text.place(0, if (centred) (height - text.height) / 2 else 0)
                end?.place(width - endWidth, if (centred) (height - endHeight) / 2 else 0)
            }
        } else {
            val height = maxOf(text.height + endHeight, constraints.minHeight)
            layout(width, height) {
                text.place(0, if (centred) (height - endHeight - text.height) / 2 else 0)
                end.place(width - endWidth, height - endHeight)
            }
        }
    }
}

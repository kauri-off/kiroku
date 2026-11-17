package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.design.theme.isLargeText

// The few StatNumbers a screen is opened for: in one row of shares, or one per line with large text (ARCHITECTURE 8.3)
// and where the row cannot give each caption its longest word. Each number stands at [alignment] in its share.
@Composable
fun StatNumbers(
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier.fillMaxWidth(),
        measurePolicy = numbersPolicy(stacked = isLargeText(), gap = Spacing.m, alignment = alignment),
    )
}

private fun numbersPolicy(
    stacked: Boolean,
    gap: Dp,
    alignment: Alignment.Horizontal,
) = MeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth
    val gapPx = gap.roundToPx()
    val widths =
        if (stacked) null else rowWidths(measurables.map { it.minIntrinsicWidth(Constraints.Infinity) }, width, gapPx)
    val shares = widths ?: measurables.map { width }
    val placeables = measurables.mapIndexed { i, it -> it.measure(Constraints(maxWidth = shares[i])) }
    val ltr = LayoutDirection.Ltr
    if (widths == null) {
        layout(width, placeables.sumOf { it.height } + gapPx * (placeables.size - 1)) {
            var y = 0
            placeables.forEach {
                it.placeRelative(alignment.align(it.width, width, ltr), y)
                y += it.height + gapPx
            }
        }
    } else {
        layout(width, placeables.maxOfOrNull { it.height } ?: 0) {
            var x = 0
            placeables.forEachIndexed { i, it ->
                it.placeRelative(x + alignment.align(it.width, widths[i], ltr), 0)
                x += widths[i] + gapPx
            }
        }
    }
}

// Each number's width in one row: equal shares, except that a number whose longest word is wider than its share gets
// that word's width and the others share the rest. Null when the longest words alone do not fit.
internal fun rowWidths(
    words: List<Int>,
    width: Int,
    gap: Int,
): List<Int>? {
    if (words.isEmpty()) return emptyList()
    val space = width - gap * (words.size - 1)
    if (words.sum() > space) return null
    val fixed = mutableSetOf<Int>()
    var share: Int
    do {
        val free = words.indices - fixed
        share = (space - fixed.sumOf { words[it] }) / free.size
        val wider = free.filter { words[it] > share }
        fixed += wider
    } while (wider.isNotEmpty() && fixed.size < words.size)
    return words.indices.map { if (it in fixed) words[it] else share }
}

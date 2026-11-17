package io.github.kauri_off.kiroku.statistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.LineOrStack
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.title.countText

// One value of a distribution, with the label a screen reader says where the shown one is a bare number.
internal class Bar(
    val label: String,
    val count: Int,
    val spokenLabel: String = label,
)

internal enum class GroupKey { Numbers, Scores, Genres, Formats, Years }

// A part of a page: numbers, or a distribution under an optional heading, with the line it shows when it is empty.
internal class Group(
    val key: GroupKey,
    val heading: String? = null,
    val bars: List<Bar> = emptyList(),
    val nothing: String? = null,
    val numbers: (@Composable () -> Unit)? = null,
)

// How a page's distributions are drawn: one line a value with the bar between label and count, the labels in one
// column; or, where the longest word of a label would leave the bars too little room, each bar under its label.
internal class BarColumns(
    val stacked: Boolean,
    val label: Dp,
    val count: Dp,
) {
    // The blank room a bar's line carries above its first line and under its last one.
    val inset: Dp get() = if (stacked) Spacing.s else Spacing.xs
}

// Measured over every label of the page, so the bars of all its parts start and end on the same lines. A label
// never breaks inside a word; it wraps between words where it is longer than its column.
@Composable
internal fun barColumns(
    bars: List<Bar>,
    width: Dp,
): BarColumns {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium
    val density = LocalDensity.current
    val labels = bars.map { it.label }
    val counts = bars.map { countText(it.count) }
    return remember(labels, counts, width, style, density) {
        fun widest(texts: List<String>): Dp =
            with(density) { (texts.maxOfOrNull { measurer.measure(it, style, softWrap = false).size.width } ?: 0).toDp() }
        val count = widest(counts)
        val words = widest(labels.flatMap { it.split(' ') })
        val column = minOf(widest(labels), width - count - Spacing.m * 2 - width * BAR_SHARE)
        BarColumns(stacked = column < words, label = column, count = count)
    }
}

// The least share of a line's width a bar keeps beside its label.
private const val BAR_SHARE = 0.4f

internal class PageMetrics(
    // The blank room above a section header's title, and under it.
    val heading: Dp,
)

@Composable
internal fun pageMetrics(): PageMetrics {
    val title =
        with(LocalDensity.current) {
            MaterialTheme.typography.titleMedium.lineHeight
                .toDp()
        }
    return PageMetrics(maxOf((LocalMinimumInteractiveComponentSize.current - title) / 2, Dp.Hairline))
}

// A line of a page: [gap] is the room from the last line above to its first line, none for the next row of a
// distribution; [lead] and [trail] are the blank room it carries above its first line and under its last one.
internal class Line(
    val gap: Dp?,
    val lead: Dp,
    val trail: Dp,
    val content: @Composable () -> Unit,
)

// A new group and every heading are Spacing.xl under the line above, a heading's first line Spacing.m above its own.
internal fun Group.lines(
    columns: BarColumns,
    metrics: PageMetrics,
): List<Line> =
    buildList {
        numbers?.let { add(Line(Spacing.xl, Dp.Hairline, Dp.Hairline, it)) }
        heading?.let { add(Line(Spacing.xl, metrics.heading, metrics.heading) { SectionHeader(it) }) }
        val first = if (heading == null) Spacing.xl else Spacing.m
        if (bars.isEmpty()) {
            nothing?.let { add(Line(first, Dp.Hairline, Dp.Hairline) { NothingLine(it) }) }
        } else {
            val lengths = barLengths(bars.map { it.count })
            bars.forEachIndexed { index, bar ->
                add(Line(if (index == 0) first else null, columns.inset, columns.inset) { BarLine(bar, lengths[index], columns) })
            }
        }
    }

// The page's first line stands where the page starts, whatever room it carries above it.
internal fun LazyListScope.lines(lines: List<Line>) {
    lines.forEachIndexed { index, line ->
        val space =
            when {
                index == 0 -> -line.lead
                line.gap == null -> Dp.Hairline
                else -> line.gap - lines[index - 1].trail - line.lead
            }
        item { Spaced(space, line.content) }
    }
}

// A bar measured against the largest value of its distribution, read out as the label and the count of titles.
@Composable
private fun BarLine(
    bar: Bar,
    fraction: Float,
    columns: BarColumns,
) {
    val count = countText(bar.count)
    val spoken = pluralStringResource(R.plurals.statistics_bar_spoken, bar.count, bar.spokenLabel, count)
    val style = MaterialTheme.typography.bodyMedium
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val semantics = Modifier.clearAndSetSemantics { contentDescription = spoken }
    if (columns.stacked) {
        Column(
            semantics.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            LineOrStack(gap = Spacing.m, modifier = Modifier.fillMaxWidth()) {
                Text(bar.label, style = style)
                Text(count, style = style, color = muted)
            }
            Indicator(fraction, Modifier.fillMaxWidth())
        }
    } else {
        Row(
            semantics.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(bar.label, style = style, modifier = Modifier.width(columns.label))
            Indicator(fraction, Modifier.weight(1f))
            Text(count, style = style, color = muted, textAlign = TextAlign.End, modifier = Modifier.width(columns.count))
        }
    }
}

// Each bar's length in a distribution: the largest value fills its bar, 0 leaves it empty.
internal fun barLengths(counts: List<Int>): List<Float> {
    val full = counts.maxOrNull() ?: 0
    return counts.map { if (full > 0) it.toFloat() / full else 0f }
}

// A static share has no end of progress to mark, and a stop dot would read as a small share.
@Composable
private fun Indicator(
    fraction: Float,
    modifier: Modifier,
) = LinearProgressIndicator(progress = { fraction }, modifier = modifier, drawStopIndicator = {})

@Composable
private fun NothingLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l),
    )
}

// A space below zero lays the line over the blank room above its own first line.
@Composable
private fun Spaced(
    space: Dp,
    content: @Composable () -> Unit,
) {
    if (space >= Dp.Hairline) {
        Column {
            Spacer(Modifier.height(space))
            content()
        }
    } else {
        Column(Modifier.trimTop(-space)) { content() }
    }
}

// Leaves out the top [amount] of the content's own room.
private fun Modifier.trimTop(amount: Dp) =
    layout { measurable, constraints ->
        val trim = amount.roundToPx()
        val placeable = measurable.measure(constraints)
        layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, -trim) }
    }

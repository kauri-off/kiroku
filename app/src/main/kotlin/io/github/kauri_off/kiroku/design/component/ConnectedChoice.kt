package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.design.theme.Spacing

// One of a few options as a connected button group, in as few rows as fit and as even as can be; the chosen one is in primary.
@Composable
fun ConnectedChoice(
    labels: List<String>,
    chosen: Int,
    onChoose: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    reselect: Boolean = false,
) {
    val widths = toggleButtonWidths(labels)
    val gap = ButtonGroupDefaults.ConnectedSpaceBetween
    BoxWithConstraints(modifier) {
        val rows = balancedRows(widths, maxWidth, gap)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { place, index ->
                        val selected = index == chosen
                        ToggleButton(
                            checked = selected,
                            onCheckedChange = { if (!selected || reselect) onChoose(index) },
                            enabled = enabled,
                            shapes = connectedShapes(first = place == 0, last = place == row.lastIndex),
                            modifier = Modifier.weight(widths[index].value).semantics { role = Role.RadioButton },
                        ) {
                            Text(labels[index])
                        }
                    }
                }
            }
        }
    }
}

// Each label's toggle button at its own width, with a pixel to spare for rounding.
@Composable
private fun toggleButtonWidths(labels: List<String>): List<Dp> {
    val measurer = rememberTextMeasurer()
    val style = ButtonDefaults.textStyleFor(ToggleButtonDefaults.MinHeight)
    val padding = ToggleButtonDefaults.contentPaddingFor(ToggleButtonDefaults.MinHeight)
    val direction = LocalLayoutDirection.current
    val sides = padding.calculateStartPadding(direction) + padding.calculateEndPadding(direction)
    return with(LocalDensity.current) {
        labels.map {
            measurer
                .measure(it, style, softWrap = false)
                .size.width
                .plus(1)
                .toDp() + sides
        }
    }
}

@Composable
private fun connectedShapes(
    first: Boolean,
    last: Boolean,
): ToggleButtonShapes =
    when {
        first && last ->
            ButtonGroupDefaults.connectedMiddleButtonShapes(shape = ButtonGroupDefaults.connectedButtonCheckedShape)
        first -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        last -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }

// The items in order in the fewest rows of at most [available] width, the widest of them as narrow as it can be.
internal fun balancedRows(
    widths: List<Dp>,
    available: Dp,
    gap: Dp,
): List<List<Int>> {
    val count = widths.size
    if (count == 0) return emptyList()

    fun width(row: List<Int>) = row.fold(gap * (row.size - 1)) { sum, index -> sum + widths[index] }
    val splits =
        (0 until (1 shl (count - 1))).map { breaks ->
            (0 until count).fold(mutableListOf(mutableListOf<Int>())) { rows, index ->
                if (index > 0 && breaks and (1 shl (index - 1)) != 0) rows += mutableListOf<Int>()
                rows.also { it.last() += index }
            }
        }
    val fitting = splits.filter { rows -> rows.all { width(it) <= available } }
    return fitting.minWithOrNull(compareBy({ it.size }, { rows -> rows.maxOf { width(it) } }))
        ?: List(count) { listOf(it) }
}

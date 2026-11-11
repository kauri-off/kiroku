package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

// A label and its value; the value takes at most three lines.
@Composable
fun FactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) = FactRow(label, listOf(value), modifier)

// A label and a value made of several parts, such as alternative titles, each starting on its own line. The parts
// share the three lines: a part cut short ends in an ellipsis and parts with no line left are not shown, so an
// ellipsis never follows a part shown whole.
@Composable
fun FactRow(
    label: String,
    values: List<String>,
    modifier: Modifier = Modifier,
) {
    ListItem(
        overlineContent = { Text(label) },
        headlineContent = { Parts(values) },
        modifier = modifier.semantics(mergeDescendants = true) {},
    )
}

@Composable
private fun Parts(values: List<String>) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    BoxWithConstraints {
        val width = constraints.maxWidth
        Column {
            var left = VALUE_LINES
            for (value in values) {
                if (left == 0) break
                val lines =
                    measurer
                        .measure(value, style, constraints = Constraints(maxWidth = width))
                        .lineCount
                Text(value, maxLines = left, overflow = TextOverflow.Ellipsis)
                left -= lines.coerceAtMost(left)
            }
        }
    }
}

private const val VALUE_LINES = 3

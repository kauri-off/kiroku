package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing

// A label and its value; the value takes at most three lines.
@Composable
fun FactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) = FactRow(label, listOf(value), modifier)

// Parts on their own lines; those that do not fit whole share the last line, so a cut ends in an ellipsis.
@Composable
fun FactRow(
    label: String,
    values: List<String>,
    modifier: Modifier = Modifier,
) {
    // Keeps the text as far from the row's edges for a tall value as for a one-line one.
    ListItem(
        overlineContent = { Text(label, Modifier.padding(top = Spacing.s)) },
        modifier = modifier,
        content = { Parts(values, Modifier.padding(bottom = Spacing.s)) },
    )
}

@Composable
private fun Parts(
    values: List<String>,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val separator = stringResource(R.string.common_list_separator)
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val lines = values.map { measurer.measure(it, style, constraints = Constraints(maxWidth = width)).lineCount }
        val whole =
            if (lines.sum() <= VALUE_LINES) {
                values.size
            } else {
                lines.runningReduce(Int::plus).indexOfFirst { it >= VALUE_LINES }
            }
        Column {
            values.take(whole).forEach { Text(it) }
            if (whole < values.size) {
                Text(
                    values.drop(whole).joinToString(separator),
                    maxLines = VALUE_LINES - lines.take(whole).sum(),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val VALUE_LINES = 3

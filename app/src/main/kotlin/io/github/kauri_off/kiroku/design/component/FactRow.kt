package io.github.kauri_off.kiroku.design.component

import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

// A label and its value; the value takes at most three lines.
@Composable
fun FactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value, maxLines = VALUE_LINES, overflow = TextOverflow.Ellipsis) },
        modifier = modifier.semantics(mergeDescendants = true) {},
    )
}

private const val VALUE_LINES = 3

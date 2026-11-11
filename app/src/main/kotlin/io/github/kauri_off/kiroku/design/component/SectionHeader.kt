package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing

// A section title with an optional "See all", whose label ends where the screen's content ends. When the title and
// the button do not fit on one line, the button moves under the title, so the title never breaks inside a word.
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Layout(
        content = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) { Text(stringResource(R.string.design_see_all)) }
            }
        },
        modifier =
            modifier
                .heightIn(min = MinHeight)
                .padding(start = Spacing.l, end = if (onSeeAll == null) Spacing.l else Spacing.xs),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val button = measurables.getOrNull(1)?.measure(loose)
        val buttonWidth = button?.width ?: 0
        val titleWidth = measurables[0].maxIntrinsicWidth(constraints.maxHeight)
        val inline = titleWidth + buttonWidth <= constraints.maxWidth
        val titleSpace = if (inline) constraints.maxWidth - buttonWidth else constraints.maxWidth
        val text = measurables[0].measure(Constraints(maxWidth = titleSpace))
        val width = constraints.maxWidth
        if (inline) {
            val height = maxOf(text.height, button?.height ?: 0, constraints.minHeight)
            layout(width, height) {
                text.place(0, (height - text.height) / 2)
                button?.place(width - buttonWidth, (height - button.height) / 2)
            }
        } else {
            val buttonHeight = button?.height ?: 0
            val height = maxOf(text.height + buttonHeight, constraints.minHeight)
            layout(width, height) {
                text.place(0, (height - buttonHeight - text.height) / 2)
                button?.place(width - buttonWidth, height - buttonHeight)
            }
        }
    }
}

private val MinHeight = 48.dp

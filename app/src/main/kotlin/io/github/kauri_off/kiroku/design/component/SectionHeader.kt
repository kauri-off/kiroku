package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
    val seeAllSpoken = stringResource(R.string.design_see_all_in, title)
    LineOrStack(
        gap = Spacing.s,
        stackedEndInset = Spacing.l - Spacing.xs,
        centred = true,
        modifier =
            modifier
                .heightIn(min = LocalMinimumInteractiveComponentSize.current)
                .padding(start = Spacing.l, end = if (onSeeAll == null) Spacing.l else Spacing.xs),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (onSeeAll != null) {
            TextButton(onClick = onSeeAll, modifier = Modifier.semantics { contentDescription = seeAllSpoken }) {
                Text(stringResource(R.string.design_see_all))
            }
        }
    }
}

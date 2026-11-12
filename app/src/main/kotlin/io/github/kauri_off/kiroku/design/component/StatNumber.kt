package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

// A number above its caption, read out together. The caller formats the number, or passes "—" for none together
// with the words a screen reader says instead ([spokenValue]).
@Composable
fun StatNumber(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    spokenValue: String? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = horizontalAlignment) {
        Text(
            value,
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = if (spokenValue == null) Modifier else Modifier.semantics { contentDescription = spokenValue },
        )
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

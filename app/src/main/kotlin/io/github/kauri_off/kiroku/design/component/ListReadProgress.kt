package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import java.text.NumberFormat

// The counted wait of a long list, where the loading state would stand: the share read so far and the line "Loading
// 3,000 of 12,400", or the count alone when the list's size is not known.
@Composable
fun ListReadProgress(
    loaded: Int,
    total: Int?,
    modifier: Modifier = Modifier,
) {
    val numbers = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val whole = total?.takeIf { it > 0 }
    val line =
        whole?.let { stringResource(R.string.design_list_loading, numbers.format(loaded), numbers.format(it)) }
            ?: pluralStringResource(R.plurals.design_list_loading_unknown_total, loaded, numbers.format(loaded))
    Box(
        modifier.fillMaxSize().padding(horizontal = Spacing.l, vertical = Spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        // One node for a screen reader: the line, read once, with the indicator's progress.
        Column(
            Modifier.widthIn(max = Sizes.messageMaxWidth).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            val indicator = Modifier.fillMaxWidth()
            if (whole != null) {
                LinearWavyProgressIndicator(
                    progress = { (loaded.toFloat() / whole).coerceIn(0f, 1f) },
                    modifier = indicator.testTag(COUNTED_WAVY_INDICATOR),
                )
            } else {
                LinearWavyProgressIndicator(indicator.testTag(WAVY_INDICATOR))
            }
            Text(line, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

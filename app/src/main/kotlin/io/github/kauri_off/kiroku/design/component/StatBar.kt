package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing
import java.text.NumberFormat
import kotlin.math.roundToInt

// One static bar of a distribution: the label, the count and its share, read out as one sentence. The value ends
// at the content edge, on the label's line or under it when both do not fit.
@Composable
fun StatBar(
    label: String,
    count: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val fraction = if (total > 0) count.toFloat() / total else 0f
    val percent = (fraction * PERCENT).roundToInt()
    val locale = LocalConfiguration.current.locales[0]
    val countText = NumberFormat.getIntegerInstance(locale).format(count)
    val percentText = NumberFormat.getPercentInstance(locale).format(percent / PERCENT.toDouble())
    val sentence = pluralStringResource(R.plurals.design_stat_bar_description, percent, label, countText, percent)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = Spacing.s)
            .clearAndSetSemantics { contentDescription = sentence },
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        LineOrStack(gap = Spacing.m, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.design_stat_bar_value, countText, percentText),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

private const val PERCENT = 100

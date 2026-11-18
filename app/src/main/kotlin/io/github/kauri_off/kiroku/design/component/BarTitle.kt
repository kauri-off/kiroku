package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

// A top bar's title on one line: a long one shrinks down to the small title size before its end is cut.
@Composable
fun BarTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize =
            TextAutoSize.StepBased(
                minFontSize = MaterialTheme.typography.titleSmall.fontSize,
                maxFontSize = LocalTextStyle.current.fontSize,
            ),
        modifier = modifier,
    )
}

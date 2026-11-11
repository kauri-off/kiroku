package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.kauri_off.kiroku.design.theme.Spacing

// A card of a horizontal strip. The title always takes its two lines, so the captions start at one height on every
// card; every card reserves the room of its full line caps, so all cards of a strip have one height whatever their
// text. It is as wide as its poster, which grows with the font scale.
@Composable
fun PosterCard(
    title: String,
    caption1: String,
    caption2: String,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleStyle = MaterialTheme.typography.bodyMedium
    val captionStyle = MaterialTheme.typography.labelMedium
    val titleColor = MaterialTheme.colorScheme.onSurface
    val captionColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .width(PosterSize.Strip.scaledWidth())
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        Poster(posterUrl, PosterSize.Strip)
        Spacer(Modifier.height(Spacing.s))
        Box {
            Column(Modifier.alpha(0f).clearAndSetSemantics {}) {
                CardText("", titleStyle, titleColor, TITLE_LINES, reserved = true)
                CardText("", captionStyle, captionColor, CAPTION1_LINES, reserved = true)
                CardText("", captionStyle, captionColor, CAPTION2_LINES, reserved = true)
            }
            Column {
                TitleText(title, TITLE_LINES, minLines = TITLE_LINES, style = titleStyle, color = titleColor)
                CardText(caption1, captionStyle, captionColor, CAPTION1_LINES, reserved = false)
                CardText(caption2, captionStyle, captionColor, CAPTION2_LINES, reserved = false)
            }
        }
        Spacer(Modifier.height(Spacing.s))
    }
}

@Composable
private fun CardText(
    text: String,
    style: TextStyle,
    color: Color,
    lines: Int,
    reserved: Boolean,
) {
    Text(
        text,
        style = style,
        color = color,
        minLines = if (reserved) lines else 1,
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

private const val TITLE_LINES = 2
private const val CAPTION1_LINES = 2
private const val CAPTION2_LINES = 1

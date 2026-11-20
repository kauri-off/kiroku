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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.kauri_off.kiroku.design.theme.Spacing

// A card of a horizontal strip. The title always takes its two lines, so the captions start at one height on every
// card; every card reserves the room of the most lines its captions may take (caption 1 takes [caption1Lines]), so all
// cards of a strip have one height whatever their text. It is as wide as its poster, which grows with the font scale. A
// card with no [onClick] opens nothing; [caption2Marked] draws caption 2 in the primary colour. [spoken] is the card's
// one sentence, said in place of its texts.
@Composable
fun PosterCard(
    title: String,
    caption1: String,
    caption2: String,
    posterUrl: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    caption1Lines: Int = CAPTION1_LINES,
    caption2Marked: Boolean = false,
    spoken: String? = null,
) {
    val titleStyle = MaterialTheme.typography.bodyMedium
    val captionStyle = MaterialTheme.typography.labelMedium
    val titleColor = MaterialTheme.colorScheme.onSurface
    val captionColor = MaterialTheme.colorScheme.onSurfaceVariant
    val caption2Color = if (caption2Marked) MaterialTheme.colorScheme.primary else captionColor
    Column(
        modifier
            .width(PosterSize.Strip.scaledWidth())
            .clip(MaterialTheme.shapes.medium)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .then(
                if (spoken ==
                    null
                ) {
                    Modifier.semantics(mergeDescendants = true) {}
                } else {
                    Modifier.clearAndSetSemantics { contentDescription = spoken }
                },
            ),
    ) {
        Poster(posterUrl, PosterSize.Strip)
        Spacer(Modifier.height(Spacing.s))
        Box {
            Column(Modifier.alpha(0f).clearAndSetSemantics {}) {
                CardText("", titleStyle, titleColor, TITLE_LINES, reserved = true)
                CardText("", captionStyle, captionColor, caption1Lines, reserved = true)
                CardText("", captionStyle, captionColor, CAPTION2_LINES, reserved = true)
            }
            Column {
                TitleText(title, TITLE_LINES, minLines = TITLE_LINES, style = titleStyle, color = titleColor)
                CardText(caption1, captionStyle, captionColor, caption1Lines, reserved = false)
                CardText(caption2, captionStyle, caption2Color, CAPTION2_LINES, reserved = false)
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

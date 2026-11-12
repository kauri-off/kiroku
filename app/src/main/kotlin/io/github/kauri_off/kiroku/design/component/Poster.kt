package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import io.github.kauri_off.kiroku.design.theme.Sizes

enum class PosterSize(
    val width: Dp,
    val height: Dp,
) {
    Row(Sizes.posterRow, Sizes.posterRowHeight),
    Strip(Sizes.posterStrip, Sizes.posterStrip * 3 / 2),
    Header(Sizes.posterHeader, Sizes.posterHeader * 3 / 2),
}

// A strip poster grows with the font scale, as its card does; a row poster shrinks with it, to leave its text room.
@Composable
internal fun PosterSize.scale(): Float {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return when (this) {
        PosterSize.Strip -> fontScale
        PosterSize.Row -> (1f / fontScale).coerceAtLeast(Sizes.posterRowNarrowest / width)
        PosterSize.Header -> 1f
    }
}

@Composable
internal fun PosterSize.scaledWidth(): Dp = width * scale()

// An image cropped to its size, with a surface-coloured placeholder while loading, on failure and when there is none.
@Composable
fun Poster(
    url: String?,
    size: PosterSize,
    modifier: Modifier = Modifier,
) {
    val scale = size.scale()
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceContainerHighest)
    AsyncImage(
        model = url,
        contentDescription = null,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .size(size.width * scale, size.height * scale)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

enum class PosterSize(
    val width: Dp,
) {
    Row(56.dp),
    Strip(104.dp),
    Header(120.dp),
}

// A strip poster grows with the font scale, as its card does; the other two keep their size.
@Composable
internal fun PosterSize.scaledWidth(): Dp =
    if (this == PosterSize.Strip) width * LocalDensity.current.fontScale.coerceAtLeast(1f) else width

// A 2:3 image with a surface-coloured placeholder while loading, on failure and when there is no image.
@Composable
fun Poster(
    url: String?,
    size: PosterSize,
    modifier: Modifier = Modifier,
) {
    val width = size.scaledWidth()
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
                .size(width, width * 3 / 2)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

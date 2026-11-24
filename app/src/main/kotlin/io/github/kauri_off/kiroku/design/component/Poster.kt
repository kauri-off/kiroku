package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import io.github.kauri_off.kiroku.design.theme.Sizes

internal val LocalRouteImages = staticCompositionLocalOf<ImageLoader?> { null }

@Composable
internal fun routeImageLoader(): ImageLoader = LocalRouteImages.current ?: SingletonImageLoader.get(LocalPlatformContext.current)

enum class PosterSize(
    val width: Dp,
    val height: Dp,
) {
    Row(Sizes.posterRow, Sizes.posterRowHeight),
    Strip(Sizes.posterStrip, Sizes.posterStrip * 3 / 2),
    Header(Sizes.posterHeader, Sizes.posterHeader * 3 / 2),
    Hero(Sizes.posterHero, Sizes.posterHero * 3 / 2),
}

// A strip poster grows with the font scale, as its card does; a row poster shrinks with it, to leave its text room.
@Composable
internal fun PosterSize.scale(): Float {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    return when (this) {
        PosterSize.Strip -> fontScale
        PosterSize.Row -> (1f / fontScale).coerceAtLeast(Sizes.posterRowNarrowest / width)
        PosterSize.Header, PosterSize.Hero -> 1f
    }
}

@Composable
internal fun PosterSize.scaledWidth(): Dp = width * scale()

// An image cropped to its size on a surface-coloured placeholder; for [large], [url]'s picture stands in or falls back.
@Composable
fun Poster(
    url: String?,
    size: PosterSize,
    modifier: Modifier = Modifier,
    large: String? = null,
) {
    val scale = size.scale()
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest
    val drawn =
        modifier
            .size(size.width * scale, size.height * scale)
            .clip(MaterialTheme.shapes.medium)
            .background(surface)
    val context = LocalPlatformContext.current
    val loader = routeImageLoader()
    val scope = rememberCoroutineScope()
    val motionScheme = MaterialTheme.motionScheme
    if (large == null) {
        val fade = remember(surface, motionScheme, scope) { PosterFade(ColorPainter(surface), motionScheme, scope) }
        AsyncImage(url, null, loader, drawn, transform = fade, contentScale = ContentScale.Crop)
    } else {
        // With nothing of [url] in memory, a failed large picture gives way to [url]'s.
        var fellBack by remember(url, large) { mutableStateOf(false) }
        val model =
            remember(context, loader, url, large, fellBack) {
                if (fellBack) {
                    url
                } else {
                    ImageRequest
                        .Builder(context)
                        .data(large)
                        .placeholderMemoryCacheKey(url)
                        .error { url?.let { loader.memoryCache?.get(MemoryCache.Key(it))?.image } }
                        .build()
                }
            }
        val fade = remember(motionScheme, scope) { PosterFade(null, motionScheme, scope) }
        AsyncImage(
            model,
            null,
            loader,
            drawn,
            transform = fade,
            onState = { if (it is AsyncImagePainter.State.Error && it.painter == null) Snapshot.withMutableSnapshot { fellBack = true } },
            contentScale = ContentScale.Crop,
        )
    }
}

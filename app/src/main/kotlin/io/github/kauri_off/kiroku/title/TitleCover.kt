package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.TitlePicture
import io.github.kauri_off.kiroku.design.component.BarTitle
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.routeImageLoader
import io.github.kauri_off.kiroku.design.theme.DialogSystemBarAppearance
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import kotlinx.coroutines.launch

internal fun hasTitleCover(
    url: String?,
    large: String?,
): Boolean = !url.isNullOrBlank() || !large.isNullOrBlank()

@Composable
internal fun TitleCover(
    url: String?,
    large: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.title_cover_open)
    Poster(
        url = url?.takeUnless(String::isBlank),
        large = large?.takeUnless { it.isBlank() || it == url },
        size = PosterSize.Hero,
        modifier =
            modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = label },
    )
}

@Composable
internal fun TitleCoverDialog(
    title: String,
    url: String?,
    large: String?,
    pictures: List<TitlePicture> = emptyList(),
    onClose: () -> Unit,
) {
    val cover = remember(url, large) { TitlePicture(url, large) }
    val sources = remember(cover) { cover.sources() }
    if (sources.isEmpty() || !LocalOnTop.current) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pages =
        remember(cover, pictures) {
            val seen = sources.toMutableSet()
            listOf(cover) +
                pictures.mapNotNull { picture ->
                    val candidates = picture.sources()
                    picture.takeIf { candidates.isNotEmpty() && candidates.none(seen::contains) }?.also { seen.addAll(candidates) }
                }
        }
    TitleCoverWindow(title, pages.size > 1, onClose) {
        if (pages.size == 1) {
            CoverImage(sources, R.string.title_cover_failed, stringResource(R.string.title_cover_image, title))
        } else {
            CoverGallery(pages, title, rememberPagerState { pages.size })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TitleCoverWindow(
    title: String,
    gallery: Boolean,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogSystemBarAppearance()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { BarTitle(title) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                painterResource(R.drawable.ic_close),
                                stringResource(if (gallery) R.string.title_gallery_close else R.string.title_cover_close),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(Spacing.l), contentAlignment = Alignment.Center) {
                content()
            }
        }
    }
}

@Composable
internal fun CoverGallery(
    pages: List<TitlePicture>,
    title: String,
    pager: PagerState,
) {
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val position = pager.targetPage.coerceIn(pages.indices)

    fun moveTo(page: Int) {
        scope.launch {
            if (pager.currentPage == page && pager.currentPageOffsetFraction == 0f) {
                pager.scrollToPage(page)
            } else {
                pager.animateScrollToPage(page, animationSpec = motion.defaultEffectsSpec())
            }
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            key = { index ->
                val picture = pages[index]
                picture.medium?.takeIf(String::isNotBlank) ?: checkNotNull(picture.large)
            },
        ) { index ->
            val description =
                if (index == 0) {
                    stringResource(R.string.title_cover_image, title)
                } else {
                    stringResource(R.string.title_gallery_image, index + 1, pages.size, title)
                }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CoverImage(pages[index].sources(), R.string.title_gallery_failed, description)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { moveTo(position - 1) },
                enabled = position > 0,
            ) { Text(stringResource(R.string.title_gallery_previous)) }
            Text(
                stringResource(R.string.title_gallery_position, pager.settledPage.coerceIn(pages.indices) + 1, pages.size),
                Modifier.padding(horizontal = Spacing.s).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onClick = { moveTo(position + 1) },
                enabled = position < pages.lastIndex,
            ) { Text(stringResource(R.string.title_gallery_next)) }
        }
    }
}

@Composable
private fun CoverImage(
    sources: List<String>,
    failure: Int,
    description: String,
) {
    var source by remember(sources) { mutableIntStateOf(0) }
    var retrying by remember(sources) { mutableStateOf(false) }
    val url = sources.getOrNull(source)
    if (url == null) {
        ScreenState(
            ScreenState.Error(stringResource(failure)) {
                retrying = true
                source = 0
            },
        )
    } else {
        key(sources, source) {
            var loaded by remember { mutableStateOf(false) }
            val context = LocalPlatformContext.current
            val request =
                remember(context, url, retrying) {
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .diskCachePolicy(if (retrying) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
                        .build()
                }
            AsyncImage(
                model = request,
                contentDescription = description.takeIf { loaded },
                imageLoader = routeImageLoader(),
                modifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { source++ },
            )
            if (!loaded) ScreenState(ScreenState.Loading)
        }
    }
}

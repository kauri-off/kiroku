package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.routeImageLoader
import io.github.kauri_off.kiroku.design.theme.DialogSystemBarAppearance
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.wiring.LoadedImageSource
import io.github.kauri_off.kiroku.wiring.captureCoverIdentity
import io.github.kauri_off.kiroku.wiring.coverSource
import kotlinx.coroutines.flow.first

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
    CoverViewer(title, pages, onClose = onClose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverViewer(
    title: String,
    pictures: List<TitlePicture>,
    initialPage: Int = 0,
    onClose: () -> Unit,
) {
    val identity = remember { GalleryIdentity() }
    var displayed by remember { mutableStateOf(pictures) }
    var pages by remember { mutableStateOf(identity.identify(pictures).also(identity::update)) }
    val pager = rememberPagerState(initialPage) { pages.size }
    LaunchedEffect(pager, pictures) {
        if (pictures == displayed) return@LaunchedEffect
        snapshotFlow { pager.isScrollInProgress }.first { !it }
        val previous = pager.settledPage.coerceIn(pages.indices)
        val selected = pages[previous].key
        val next = identity.identify(pictures)
        val destination = next.indexOfFirst { it.key == selected }.takeIf { it >= 0 } ?: previous.coerceIn(next.indices)
        identity.update(next)
        pages = next
        displayed = pictures
        pager.requestScrollToPage(destination)
    }

    val loader = routeImageLoader()
    val loaded = remember(loader) { mutableStateMapOf<GalleryPage, LoadedImageSource>() }
    val gallery = pages.size > 1
    val snackbars = remember { SnackbarHostState() }

    fun selectedImage(): LoadedImageSource? {
        if (pager.isScrollInProgress || displayed != pictures) return null
        val page = pages.getOrNull(pager.settledPage) ?: return null
        if (pager.layoutInfo.visiblePagesInfo.none { it.index == pager.settledPage && it.key == page.key }) return null
        return loaded[page]
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DialogSystemBarAppearance()
        Scaffold(
            snackbarHost = { ScreenSnackbarHost(snackbars) },
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
                    actions = { key(loader) { CoverExportActions(title, loader, snackbars, ::selectedImage) } },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(Spacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    userScrollEnabled = gallery,
                    key = { pages[it].key },
                ) { index ->
                    val page = pages[index]
                    val description =
                        if (index == 0) {
                            stringResource(R.string.title_cover_image, title)
                        } else {
                            stringResource(R.string.title_gallery_image, index + 1, pages.size, title)
                        }
                    val failure = if (gallery) R.string.title_gallery_failed else R.string.title_cover_failed
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        key(loader, page) {
                            CoverImage(page.picture.sources(), failure, description) { image ->
                                DisposableEffect(image) {
                                    loaded[page] = image
                                    onDispose { loaded.remove(page) }
                                }
                            }
                        }
                    }
                }
                if (gallery) {
                    Text(
                        stringResource(R.string.title_gallery_position, pager.settledPage.coerceIn(pages.indices) + 1, pages.size),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private class GalleryIdentity {
    private var shown: List<GalleryPage> = emptyList()
    private var nextKey = 0L

    fun identify(pictures: List<TitlePicture>): List<GalleryPage> {
        val keysBySource = shown.flatMap { page -> page.picture.sources().map { it to page.key } }.toMap()
        val keysByMedium = shown.mapNotNull { page -> page.picture.medium?.let { it to page.key } }.toMap()
        val assigned = mutableSetOf<Long>()
        val mediumKeys = pictures.map { keysByMedium[it.medium]?.takeIf(assigned::add) }
        var key = nextKey
        return pictures.mapIndexed { index, picture ->
            val previous = mediumKeys[index] ?: picture.sources().firstNotNullOfOrNull { keysBySource[it]?.takeIf(assigned::add) }
            GalleryPage(previous ?: key++, picture)
        }
    }

    fun update(pages: List<GalleryPage>) {
        shown = pages
        nextKey = maxOf(nextKey, pages.maxOf { it.key } + 1)
    }
}

private data class GalleryPage(
    val key: Long,
    val picture: TitlePicture,
)

@Composable
private fun CoverImage(
    sources: List<String>,
    failure: Int,
    description: String,
    onLoaded: @Composable (LoadedImageSource) -> Unit,
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
            var loaded by remember { mutableStateOf<LoadedImageSource?>(null) }
            val attempt = source
            val context = LocalPlatformContext.current
            val request =
                remember(context, url, retrying) {
                    ImageRequest
                        .Builder(context)
                        .data(url)
                        .apply { extras[captureCoverIdentity] = true }
                        .memoryCachePolicy(if (retrying) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
                        .diskCachePolicy(if (retrying) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
                        .build()
                }
            AsyncImage(
                model = request,
                contentDescription = description.takeIf { loaded != null },
                imageLoader = routeImageLoader(),
                modifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = it.result.coverSource(url) },
                onError = { if (source == attempt) source = attempt + 1 },
            )
            val image = loaded
            if (image == null) ScreenState(ScreenState.Loading) else onLoaded(image)
        }
    }
}

package io.github.kauri_off.kiroku.title

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.BarTitle
import io.github.kauri_off.kiroku.design.component.LocalOnTop
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.routeImageLoader
import io.github.kauri_off.kiroku.design.theme.DialogSystemBarAppearance
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TitleCoverDialog(
    title: String,
    url: String?,
    large: String?,
    onClose: () -> Unit,
) {
    val sources = remember(url, large) { listOfNotNull(large, url).filterNot(String::isBlank).distinct() }
    if (sources.isEmpty() || !LocalOnTop.current) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
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
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.title_cover_close))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(Spacing.l), contentAlignment = Alignment.Center) {
                CoverImage(title, sources)
            }
        }
    }
}

@Composable
private fun CoverImage(
    title: String,
    sources: List<String>,
) {
    var source by remember(sources) { mutableIntStateOf(0) }
    var retrying by remember(sources) { mutableStateOf(false) }
    val url = sources.getOrNull(source)
    if (url == null) {
        ScreenState(
            ScreenState.Error(stringResource(R.string.title_cover_failed)) {
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
                contentDescription = if (loaded) stringResource(R.string.title_cover_image, title) else null,
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

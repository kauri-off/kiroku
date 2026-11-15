package io.github.kauri_off.kiroku.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing

enum class LinksEvent { Back, OpenSettings }

// Says once that the system opens MAL's links in the app only when the person allows it, and leads to that setting.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinksScreen(
    onEvent: (LinksEvent) -> Unit,
    snackbars: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.links_title)) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(LinksEvent.Back) }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        // Centred in the window; scrolls when large text makes it taller.
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier =
                    Modifier
                        .widthIn(max = Sizes.contentMaxWidth)
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.l, vertical = Spacing.xl),
            ) {
                Text(
                    stringResource(R.string.links_sentence),
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = LineBreak.Heading),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = Sizes.messageMaxWidth),
                )
                Button(
                    onClick = { onEvent(LinksEvent.OpenSettings) },
                    shapes = ButtonDefaults.shapesFor(ButtonHeight),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonHeight),
                    modifier =
                        Modifier
                            .padding(top = Spacing.xl)
                            .heightIn(min = ButtonHeight)
                            .widthIn(max = Sizes.messageMaxWidth)
                            .fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.links_open_settings),
                        style = ButtonDefaults.textStyleFor(ButtonHeight),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private val ButtonHeight = ButtonDefaults.MediumContainerHeight

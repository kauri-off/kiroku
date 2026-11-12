package io.github.kauri_off.kiroku.titlesub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.design.component.ScreenSnackbarHost
import io.github.kauri_off.kiroku.design.component.ScreenState
import io.github.kauri_off.kiroku.design.component.SessionBanner
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.title.message

// What a sub-screen of section 9.9 shows under its top app bar: a state of the shared component, or its one list.
internal sealed interface SubScreenBody {
    data class State(
        val state: ScreenState,
    ) : SubScreenBody

    data class List(
        val content: LazyListScope.() -> Unit,
    ) : SubScreenBody
}

// A top app bar with Back and the screen's name, the session banner while the session is expired (S6), and one list
// centred at the content width. Snackbars go to [snackbars], the shell's host.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubScreen(
    title: String,
    sessionExpired: Boolean,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    snackbars: SnackbarHostState,
    body: SubScreenBody,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (sessionExpired) SessionBanner(onSignIn = onSignIn)
            }
        },
        snackbarHost = { ScreenSnackbarHost(snackbars) },
    ) { padding ->
        when (body) {
            is SubScreenBody.State -> ScreenState(body.state, Modifier.padding(padding))
            is SubScreenBody.List -> CentredList(padding, body.content)
        }
    }
}

@Composable
private fun CentredList(
    padding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    val direction = LocalLayoutDirection.current
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction)),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.s),
            content = content,
        )
    }
}

@Composable
internal fun errorState(
    error: DataError,
    onRetry: () -> Unit,
): ScreenState = ScreenState.Error(stringResource(error.message()), onRetry)

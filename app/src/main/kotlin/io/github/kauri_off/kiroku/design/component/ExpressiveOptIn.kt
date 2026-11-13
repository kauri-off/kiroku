@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// The only file that opts in to Material 3 Expressive API that is still experimental: the loading indicator.

// A wait of unknown, short length; until shown, only the indicator's room.
@Composable
fun WaitIndicator(
    modifier: Modifier = Modifier,
    shown: Boolean = true,
) {
    if (shown) {
        LoadingIndicator(modifier)
    } else {
        Box(modifier.size(LoadingIndicatorDefaults.ContainerWidth, LoadingIndicatorDefaults.ContainerHeight))
    }
}

// Pull-to-refresh whose indicator is the loading indicator.
@Composable
fun RefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        contentAlignment = contentAlignment,
        indicator = { PullToRefreshDefaults.LoadingIndicator(state, isRefreshing, Modifier.align(Alignment.TopCenter)) },
        content = content,
    )
}

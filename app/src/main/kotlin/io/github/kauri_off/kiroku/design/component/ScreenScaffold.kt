package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex

@Composable
internal fun ScreenScaffold(
    topBar: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.semantics { isTraversalGroup = true },
        topBar = {
            Column(
                Modifier.semantics {
                    isTraversalGroup = true
                    traversalIndex = -1f
                },
            ) { topBar() }
        },
        snackbarHost = snackbarHost,
        content = content,
    )
}

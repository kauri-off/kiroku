package io.github.kauri_off.kiroku.design.component

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.github.kauri_off.kiroku.design.theme.Sizes

// A button in a list item's trailing slot. The list item counts its own padding into the slot's touch target, which
// holds for a row that is one target; a button there is a target of its own and keeps the full size.
@Composable
fun TrailingControl(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Sizes.touchTarget, content = content)
}

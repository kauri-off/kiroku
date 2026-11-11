package io.github.kauri_off.kiroku.design.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Sizes {
    val posterRow: Dp = 56.dp
    val posterStrip: Dp = 104.dp
    val posterHeader: Dp = 120.dp

    // Content is centred at this width on wider windows.
    val contentMaxWidth: Dp = 640.dp

    // Keeps a state message of a few words in one or two balanced lines on wide windows.
    val messageMaxWidth: Dp = 320.dp
}

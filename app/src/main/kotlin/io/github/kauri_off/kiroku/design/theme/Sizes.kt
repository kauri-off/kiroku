package io.github.kauri_off.kiroku.design.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object Sizes {
    // A list row's poster, in a MAL poster's proportions, large enough to recognise its art.
    val posterRow: Dp = 72.dp
    val posterRowHeight: Dp = 104.dp

    // The row poster narrows with the font scale down to this width.
    val posterRowNarrowest: Dp = 48.dp
    val posterStrip: Dp = 104.dp
    val posterHeader: Dp = 120.dp

    // Content is centred at this width on wider windows.
    val contentMaxWidth: Dp = 640.dp

    // Keeps a state message of a few words in one or two balanced lines on wide windows.
    val messageMaxWidth: Dp = 320.dp

    // A tab carries its own padding, so a scrollable tab row with none starts its first label on the content edge.
    val tabRowEdge: Dp = 0.dp

    // The least a control is tall and wide to the touch.
    val touchTarget: Dp = 48.dp
}

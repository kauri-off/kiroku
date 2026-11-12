package io.github.kauri_off.kiroku.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

// The font scale from which the layout changes for large text (ARCHITECTURE 8.3).
const val LARGE_TEXT_SCALE = 1.5f

@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE

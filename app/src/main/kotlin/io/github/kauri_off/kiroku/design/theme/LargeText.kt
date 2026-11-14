package io.github.kauri_off.kiroku.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

// Large text (ARCHITECTURE 8.3): a font scale of 1.5 or more.
@Composable
fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT

const val LARGE_TEXT = 1.5f

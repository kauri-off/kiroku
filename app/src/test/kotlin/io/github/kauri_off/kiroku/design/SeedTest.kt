package io.github.kauri_off.kiroku.design

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The hand-written schemes: text roles are legible on their containers, light and dark of a seed share its hues,
// and the window behind the first frame is the first seed's surface.
@RunWith(RobolectricTestRunner::class)
class SeedTest {
    private fun ColorScheme.textPairs(): List<Triple<String, Color, Color>> =
        listOf(
            Triple("primary", onPrimary, primary),
            Triple("primaryContainer", onPrimaryContainer, primaryContainer),
            Triple("secondary", onSecondary, secondary),
            Triple("secondaryContainer", onSecondaryContainer, secondaryContainer),
            Triple("tertiary", onTertiary, tertiary),
            Triple("tertiaryContainer", onTertiaryContainer, tertiaryContainer),
            Triple("error", onError, error),
            Triple("errorContainer", onErrorContainer, errorContainer),
            Triple("background", onBackground, background),
            Triple("inverseSurface", inverseOnSurface, inverseSurface),
        ) +
            listOf(
                surface,
                surfaceVariant,
                surfaceContainerLowest,
                surfaceContainerLow,
                surfaceContainer,
                surfaceContainerHigh,
                surfaceContainerHighest,
            ).flatMap { listOf(Triple("onSurface", onSurface, it), Triple("onSurfaceVariant", onSurfaceVariant, it)) } +
            listOf(Triple("primary on surface", primary, surface), Triple("tertiary on surface", tertiary, surface))

    private fun contrast(
        a: Color,
        b: Color,
    ): Float = (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)

    private fun hue(color: Color): Float = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }[0]

    @Test
    fun textRolesAreLegible() {
        for (seed in Seed.entries) {
            for (dark in listOf(false, true)) {
                for ((name, text, container) in seed.scheme(dark).textPairs()) {
                    val ratio = contrast(text, container)
                    assertTrue("$seed ${if (dark) "dark" else "light"} $name: $ratio", ratio >= MIN_CONTRAST)
                }
            }
        }
    }

    @Test
    fun lightAndDarkShareTheSeedsHues() {
        for (seed in Seed.entries) {
            for (role in listOf<(ColorScheme) -> Color>({ it.primary }, { it.secondary }, { it.tertiary })) {
                val difference = abs(hue(role(seed.light)) - hue(role(seed.dark))).let { min(it, 360 - it) }
                assertTrue("$seed: $difference°", difference <= MAX_HUE_DRIFT)
            }
        }
    }

    @Test
    fun windowIsTheSurfaceInLight() =
        assertEquals(
            Seed.Teal.light.surface
                .toArgb(),
            windowBackground(),
        )

    @Test
    @Config(qualifiers = "night")
    fun windowIsTheSurfaceInDark() =
        assertEquals(
            Seed.Teal.dark.surface
                .toArgb(),
            windowBackground(),
        )

    private fun windowBackground(): Int = ContextCompat.getColor(RuntimeEnvironment.getApplication(), R.color.window_background)

    private companion object {
        const val MIN_CONTRAST = 4.5f
        const val MAX_HUE_DRIFT = 15f
    }
}

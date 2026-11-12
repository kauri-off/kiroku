package io.github.kauri_off.kiroku.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TypographyTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `every emphasised role is heavier than its plain one at the same size`() {
        lateinit var type: Typography
        compose.setContent { KirokuTheme(dark = false, dynamicColor = false) { type = MaterialTheme.typography } }
        val pairs =
            with(type) {
                listOf(
                    displayLarge to displayLargeEmphasized,
                    displayMedium to displayMediumEmphasized,
                    displaySmall to displaySmallEmphasized,
                    headlineLarge to headlineLargeEmphasized,
                    headlineMedium to headlineMediumEmphasized,
                    headlineSmall to headlineSmallEmphasized,
                    titleLarge to titleLargeEmphasized,
                    titleMedium to titleMediumEmphasized,
                    titleSmall to titleSmallEmphasized,
                    bodyLarge to bodyLargeEmphasized,
                    bodyMedium to bodyMediumEmphasized,
                    bodySmall to bodySmallEmphasized,
                    labelLarge to labelLargeEmphasized,
                    labelMedium to labelMediumEmphasized,
                    labelSmall to labelSmallEmphasized,
                )
            }
        for ((plain, emphasised) in pairs) {
            assertTrue("$emphasised is not heavier than $plain", emphasised.fontWeight!! > plain.fontWeight!!)
            assertEquals(plain.fontSize, emphasised.fontSize)
            assertEquals(plain.lineHeight, emphasised.lineHeight)
        }
    }
}

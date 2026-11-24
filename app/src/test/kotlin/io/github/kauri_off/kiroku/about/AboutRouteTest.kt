package io.github.kauri_off.kiroku.about

import android.content.Intent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.design.theme.Sizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w840dp-h600dp-xhdpi")
class AboutRouteTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private fun show(
        snackbars: SnackbarHostState = SnackbarHostState(),
        onBack: () -> Unit = {},
    ) {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { AboutRoute(snackbars, onBack) }
        }
    }

    @Test
    fun showsTheInstalledBuildAndOpensOnlyTheSelectedDestination() {
        var wentBack = false
        show { wentBack = true }
        val activity = shadowOf(compose.activity)

        compose.onNodeWithText("About").assertIsDisplayed()
        compose.onNodeWithText("Kiroku debug").assertIsDisplayed()
        compose.onNodeWithText("Version\u00A0${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        assertNull(activity.nextStartedActivity)

        val root = "https://github.com/kauri-off/kiroku"
        val links =
            mapOf(
                "Project on GitHub, opens externally" to root,
                "License, GPL-3.0-or-later, opens externally" to "$root/blob/main/LICENSE",
                "Privacy statement, opens externally" to "$root/blob/main/PRIVACY.md",
            )
        for ((label, url) in links) {
            compose.onNodeWithContentDescription(label).assertHasClickAction().performClick()
            val intent = activity.nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
            assertNull(activity.nextStartedActivity)
        }

        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(wentBack)
        assertNull(activity.nextStartedActivity)
    }

    @Test
    fun staysOnAboutWhenNoAppCanOpenTheLink() {
        val snackbars = SnackbarHostState()
        val application = shadowOf(compose.activity.application)
        application.checkActivities(true)
        show(snackbars)
        compose.onNodeWithContentDescription("Project on GitHub, opens externally").performClick()
        compose.runOnIdle {
            assertEquals("No app can open this link", snackbars.currentSnackbarData?.visuals?.message)
            assertNull(shadowOf(compose.activity).nextStartedActivity)
        }
        compose.onNodeWithText("About").assertIsDisplayed()
    }

    @Test
    fun centresTheActionsAtTheSharedContentCap() {
        show()
        val actions =
            listOf("Project on GitHub", "License", "Privacy statement").map {
                compose.onNodeWithContentDescription(it, substring = true).getUnclippedBoundsInRoot()
            }
        actions.forEach { row ->
            assertEquals(Sizes.contentMaxWidth, row.right - row.left)
            assertEquals(840.dp, row.left + row.right)
        }
        actions.zipWithNext().forEach { (above, below) -> assertTrue(above.bottom <= below.top) }
    }

    @Test
    @Config(qualifiers = "ru-w600dp-h960dp-xhdpi")
    fun russianLabelsAndActionsFitTheNarrowerWideWindow() {
        show()
        compose.onNodeWithText("О приложении").assertIsDisplayed()
        compose.onNodeWithText("Версия\u00A0${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        val privacy = compose.onNodeWithContentDescription("Политика конфиденциальности, открывается в другом приложении")
        privacy.assertHasClickAction().assertIsDisplayed()
        val row = privacy.getUnclippedBoundsInRoot()
        assertEquals(600.dp, row.right - row.left)
    }
}

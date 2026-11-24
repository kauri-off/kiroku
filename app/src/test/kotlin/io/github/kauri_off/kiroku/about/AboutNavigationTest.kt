package io.github.kauri_off.kiroku.about

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.design.theme.Sizes
import io.github.kauri_off.kiroku.link.LinkGraphApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = LinkGraphApplication::class, sdk = [32], qualifiers = "en-w840dp-h600dp-xhdpi")
class AboutNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openAbout(
        profile: String,
        settings: String,
        about: String,
    ) {
        compose.onNode(hasText(profile) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription(settings).performClick()
        compose.onNode(hasText(about) and hasClickAction()).performClick()
        compose.onNodeWithText(about).assertIsDisplayed()
    }

    @Test
    fun aboutStaysOnTheProfileStackAfterExternalLaunchAndActivityRecreation() {
        openAbout("Profile", "Settings", "About")
        val rail = compose.onNode(hasText("Profile") and hasClickAction()).getUnclippedBoundsInRoot()
        val project = compose.onNodeWithContentDescription("Project on GitHub, opens externally")
        val row = project.getUnclippedBoundsInRoot()
        assertEquals(Sizes.contentMaxWidth, row.right - row.left)
        assertTrue(rail.right < row.left)
        assertTrue(row.right < 840.dp)
        assertTrue(row.left > 840.dp - row.right)

        val activity = shadowOf(compose.activity)
        assertNull(activity.nextStartedActivity)
        project.performClick()
        val intent = activity.nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/kauri-off/kiroku", intent.dataString)
        assertNull(activity.nextStartedActivity)

        val scenario = compose.activityRule.scenario
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("About").assertIsDisplayed()

        scenario.recreate()
        compose.onNodeWithText("About").assertIsDisplayed()
        assertNull(shadowOf(compose.activity).nextStartedActivity)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Title language").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-w600dp-h960dp-xhdpi")
    fun russianAboutFitsBesideTheRailAndItsBackReturnsToSettings() {
        openAbout("Профиль", "Настройки", "О приложении")
        val rail = compose.onNode(hasText("Профиль") and hasClickAction()).getUnclippedBoundsInRoot()
        val privacy = compose.onNodeWithContentDescription("Политика конфиденциальности, открывается в другом приложении")
        val row = privacy.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(rail.right <= row.left)
        assertEquals(600.dp, row.right)

        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Настройки").assertIsDisplayed()
        privacy.assertDoesNotExist()
    }

    @Test
    fun aboutKeyKeepsItsSavedNavigationName() {
        val name = "io.github.kauri_off.kiroku.about.AboutKey"
        assertEquals(name, AboutKey.javaClass.name)
        assertEquals(name, AboutKey.serializer().descriptor.serialName)
    }
}

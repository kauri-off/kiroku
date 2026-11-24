package io.github.kauri_off.kiroku.shell

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.link.LinkGraphApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = LinkGraphApplication::class, sdk = [32], qualifiers = "en-w840dp-h600dp-xhdpi")
class ScreenTraversalTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun listAndTitlePrioritizeTheirHeadersWithinTheScreen() {
        val title = hasText(FixtureAnime.exactToWatch.titles.romaji)
        val row = title and hasClickAction()
        assertHeaderBeforeBody(row, "Filter by title", "Sort")
        compose.onNode(row).performClick()
        assertHeaderBeforeBody(title and isHeading(), "Back", "More options")
    }

    @Test
    fun profileSettingsAndAboutPrioritizeTheirHeadersWithinTheScreen() {
        compose.onNode(railItem("Profile")).performClick()
        assertHeaderBeforeBody(hasText("kiroku_demo"), "Settings")
        compose.onNodeWithContentDescription("Settings").performClick()
        assertHeaderBeforeBody(hasText("Title language") and hasClickAction(), "Back")
        compose.onNode(hasText("About") and hasClickAction()).performClick()
        assertHeaderBeforeBody(hasContentDescription("Project on GitHub, opens externally") and hasClickAction(), "Back")
    }

    private fun assertHeaderBeforeBody(
        body: SemanticsMatcher,
        vararg actions: String,
    ) {
        val group = SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true)
        val header = group and SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f)
        val screen = group and hasAnyDescendant(header) and hasAnyDescendant(body)
        compose.onNode(body and hasAnyAncestor(screen) and !hasAnyAncestor(header)).assertIsDisplayed()
        for (action in actions) {
            val toolbarAction = hasContentDescription(action) and hasClickAction()
            compose.onNode(toolbarAction and hasAnyAncestor(header and hasAnyAncestor(screen))).assertIsDisplayed()
        }
        for (label in listOf("List", "Search", "Profile")) {
            compose.onNode(railItem(label) and hasAnyAncestor(group) and !hasAnyAncestor(screen)).assertIsDisplayed()
        }
    }

    private fun railItem(label: String) = hasText(label) and hasClickAction()
}

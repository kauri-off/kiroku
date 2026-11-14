package io.github.kauri_off.kiroku.wiring

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.source.DataSource
import io.github.kauri_off.kiroku.source.StartsOn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// The launcher activity opens the real app on demo data, and an open edit sheet with a typed count survives
// recreation (a rotation).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class MainActivityTest {
    @get:Rule(order = 0)
    val source = StartsOn(DataSource.Demo)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun `the app opens on the demo list and keeps an open edit sheet across recreation`() {
        val title = FixtureAnime.exactToWatch.titles.romaji
        compose.waitForIdle()
        compose.onNodeWithText("My list").assertExists()
        compose.onNodeWithText(title).performTouchInput { longClick() }
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Episodes watched"))
        field.performTextReplacement("1")
        field.performTextReplacement("17")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("17")).assertExists()
    }
}

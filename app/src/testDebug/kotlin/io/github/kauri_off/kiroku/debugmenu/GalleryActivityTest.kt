package io.github.kauri_off.kiroku.debugmenu

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class GalleryActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<GalleryActivity>()

    @Test
    fun `the gallery shows the components, and its back arrow returns to the menu`() {
        compose.onNodeWithText("Components").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(compose.activity.isFinishing)
    }
}

package io.github.kauri_off.kiroku.notify

import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.link.LinkGraphApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

// A tap on an episode's notification opens its title with the List beneath it (section 6.3), from a start and while the app runs.
@RunWith(RobolectricTestRunner::class)
@Config(application = LinkGraphApplication::class, qualifiers = "en-w411dp-h891dp")
class NotificationTapTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as LinkGraphApplication
    private val kagerou = FixtureAnime.exactToWatch
    private val shiokaze = FixtureAnime.exactCaughtUp
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    private fun start(intent: Intent): MainActivity {
        scenario = ActivityScenario.launch(intent)
        compose.waitForIdle()
        var activity: MainActivity? = null
        scenario!!.onActivity { activity = it }
        return activity!!
    }

    private fun onPageOf(anime: Anime) {
        assertEquals(1, compose.onAllNodes(isHeading() and hasText(anime.titles.romaji)).fetchSemanticsNodes().size)
        compose.onNodeWithText("My list").assertDoesNotExist()
    }

    private fun back(activity: MainActivity) {
        scenario!!.onActivity { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    // Back shows the List, and Back from it leaves the app.
    private fun assertTheListIsBeneath(activity: MainActivity) {
        back(activity)
        compose.onNodeWithText("My list").assertExists()
        back(activity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a tap that starts the app opens on its title with the List beneath`() {
        val activity = start(tapIntent(app, kagerou.id))
        onPageOf(kagerou)
        // The start never shows the List first.
        assertEquals(0, app.test.wholeListReads)
        assertTheListIsBeneath(activity)
    }

    @Test
    fun `a tap while the app shows another title opens its own over the List alone`() {
        val activity = start(tapIntent(app, kagerou.id))
        onPageOf(kagerou)
        val tap = ReflectionHelpers.ClassParameter.from(Intent::class.java, tapIntent(app, shiokaze.id))
        scenario!!.onActivity { ReflectionHelpers.callInstanceMethod<Unit>(it, "onNewIntent", tap) }
        compose.waitForIdle()
        onPageOf(shiokaze)
        assertTheListIsBeneath(activity)
    }

    @Test
    fun `the tap replayed from the recent tasks opens the List, not the title again`() {
        start(tapIntent(app, kagerou.id).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        compose.onNodeWithText("My list").assertExists()
        assertEquals(0, compose.onAllNodes(isHeading() and hasText(kagerou.titles.romaji)).fetchSemanticsNodes().size)
    }
}

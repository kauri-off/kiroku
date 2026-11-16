package io.github.kauri_off.kiroku.airingcounts

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureTime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.debugmenu.DebugMenuActivity
import io.github.kauri_off.kiroku.wiring.AppGraphOwner
import io.github.kauri_off.kiroku.wiring.MovingClock
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration

// The demo, its list and airing data stored an hour ago: stale, so that a refresh or a sync would ask its sources.
class StaleDemoApplication :
    Application(),
    AppGraphOwner {
    private val clock = MovingClock(Fixtures.reference.now, FixtureTime.zone)

    val demo = Demo(Fixtures.reference, CoroutineScope(SupervisorJob() + Dispatchers.Main), clock = clock)

    override val graph = TestGraph(demo)

    init {
        clock.instant += Duration.ofHours(1)
    }
}

// The section in the open menu: what is stored, counted, with nothing asked of a source.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp", application = StaleDemoApplication::class)
class AiringCountsSectionTest {
    @get:Rule
    val compose = createAndroidComposeRule<DebugMenuActivity>()

    private val demo get() = (RuntimeEnvironment.getApplication() as StaleDemoApplication).demo

    private fun shown(text: String) {
        compose.waitUntil(TIMEOUT) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text, substring = true).performScrollTo().assertExists()
    }

    @Test
    fun `opening the section counts the stored titles and sends no request`() {
        shown("Tracked titles")
        shown(FixtureAnime.estimatedCaughtUp.titles.romaji)
        shown("Totals: MAL\u00A012, AniList\u00A013")
        shown("AniList numbers episodes\u00A013–24")
        compose.waitForIdle()

        assertEquals(emptyList<Any>(), demo.server.requests)
        assertEquals(emptyList<Any>(), demo.airingSource.requests)
    }

    @Test
    fun `a store that cannot be read is said, and Retry reads it again`() {
        shown("Tracked titles")
        demo.store.unreadable.value = true
        shown("The list or its airing data could not be read")

        demo.store.unreadable.value = false
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        shown("Tracked titles")
        assertEquals(emptyList<Any>(), demo.airingSource.requests)
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}

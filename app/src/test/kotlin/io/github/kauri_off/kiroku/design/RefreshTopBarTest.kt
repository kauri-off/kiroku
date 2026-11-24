package io.github.kauri_off.kiroku.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.list.FixtureListScreen
import io.github.kauri_off.kiroku.list.ListEvent
import io.github.kauri_off.kiroku.list.ListFixtureStates
import io.github.kauri_off.kiroku.list.UserListFixtureStates
import io.github.kauri_off.kiroku.profile.ProfileEvent
import io.github.kauri_off.kiroku.profile.ProfileFixtureStates
import io.github.kauri_off.kiroku.profile.ProfileScreen
import io.github.kauri_off.kiroku.profile.ProfileState
import io.github.kauri_off.kiroku.search.resultRow
import io.github.kauri_off.kiroku.title.TitleEvent
import io.github.kauri_off.kiroku.title.TitleFixtures
import io.github.kauri_off.kiroku.title.TitleScreen
import io.github.kauri_off.kiroku.titlesub.RecommendationsContent
import io.github.kauri_off.kiroku.titlesub.RecommendationsEvent
import io.github.kauri_off.kiroku.titlesub.RecommendationsScreen
import io.github.kauri_off.kiroku.titlesub.RecommendationsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h640dp-xhdpi")
class RefreshTopBarTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val snackbars = SnackbarHostState()
    private lateinit var colors: ColorScheme
    private var refreshes = 0

    @Test
    fun ownProfileKeepsItsIdleColorAfterAnUnderThresholdPull() {
        show { profile(ProfileFixtureStates.me()) }
        assertShortPullKeepsIdleColor()
        assertRealScrollChangesColor()
        assertLongPullRefreshes()
    }

    @Test
    fun ownProfileKeepsItsIdleColorAfterTouchCancellation() {
        show { profile(ProfileFixtureStates.me()) }
        val idle = barColor()
        shortPull(cancel = true)
        assertEquals(0f, scrollOffset(), 0f)
        assertEquals(0, refreshes)
        assertEquals(idle, barColor())
    }

    @Test
    fun anotherProfileKeepsItsIdleColorAfterAnUnderThresholdPull() {
        show { profile(ProfileFixtureStates.of(ProfileFixtureStates.otherName, ProfileFixtureStates.other)) }
        assertShortPullKeepsIdleColor()
        assertRealScrollChangesColor()
        assertLongPullRefreshes()
    }

    @Test
    fun titleKeepsItsIdleColorAfterAnUnderThresholdPull() {
        show {
            TitleScreen(TitleFixtures.of(FixtureAnime.exactToWatch), { if (it == TitleEvent.Refresh) refreshes++ }, snackbars)
        }
        assertShortPullKeepsIdleColor()
        assertRealScrollChangesColor()
        assertLongPullRefreshes()
    }

    @Test
    fun recommendationsKeepsItsIdleColorAfterAnUnderThresholdPull() {
        val rows = Fixtures.reference.entries.map { resultRow(it.anime, it.values, TitleLanguage.Romaji) }
        show {
            RecommendationsScreen(
                RecommendationsState(RecommendationsContent.Shown(rows)),
                onEvent = { if (it == RecommendationsEvent.Reload) refreshes++ },
                snackbars = snackbars,
            )
        }
        assertShortPullKeepsIdleColor()
        assertRealScrollChangesColor()
        assertLongPullRefreshes()
    }

    @Test
    fun ownListKeepsItsIdleColorAfterAnUnderThresholdPull() {
        show { FixtureListScreen(ListFixtureStates.planned) { if (it == ListEvent.Refresh) refreshes++ } }
        assertShortPullKeepsIdleColor()
        assertLongPullRefreshes()
    }

    @Test
    fun anotherUsersListKeepsItsIdleColorAfterAnUnderThresholdPull() {
        show { FixtureListScreen(UserListFixtureStates.watching) { if (it == ListEvent.Refresh) refreshes++ } }
        assertShortPullKeepsIdleColor()
        assertLongPullRefreshes()
    }

    @Composable
    private fun profile(state: ProfileState) = ProfileScreen(state, { if (it == ProfileEvent.Refresh) refreshes++ }, snackbars)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                colors = MaterialTheme.colorScheme
                content()
            }
        }
    }

    private fun barColor() = compose.onRoot().captureToImage().toPixelMap()[2, 32]

    private fun scrollOffset(): Float =
        compose
            .onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun shortPull(cancel: Boolean = false) {
        compose.onRoot().performTouchInput {
            down(center)
            moveBy(Offset(0f, 48.dp.toPx()))
            if (cancel) cancel() else up()
        }
        compose.waitForIdle()
    }

    private fun assertShortPullKeepsIdleColor() {
        val idle = barColor()
        shortPull()
        assertEquals(0, refreshes)
        assertEquals(idle, barColor())
    }

    private fun assertRealScrollChangesColor() {
        assertEquals(0f, scrollOffset(), 0f)
        compose.onRoot().performTouchInput { swipeUp(startY = centerY, endY = centerY - 96.dp.toPx()) }
        assertTrue(scrollOffset() > 0f)
        assertEquals(colors.surfaceContainer, barColor())
        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = centerY + 112.dp.toPx()) }
        assertEquals(0f, scrollOffset(), 0f)
        assertEquals(colors.surface, barColor())
        assertEquals(0, refreshes)
    }

    private fun assertLongPullRefreshes() {
        val idle = barColor()
        compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
        compose.waitForIdle()
        assertEquals(1, refreshes)
        assertEquals(idle, barColor())
    }
}

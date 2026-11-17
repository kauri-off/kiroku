package io.github.kauri_off.kiroku.titlesub

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class ScoresScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<ScoresEvent>()

    private fun show(state: ScoresState) {
        compose.setContent {
            KirokuTheme(
                dark = false,
                dynamicColor = false,
            ) { ScoresScreen(state, { events += it }, SnackbarHostState()) }
        }
        compose.waitForIdle()
    }

    @Test
    fun everyScoreFromTenDownIsABarSpokenAsThatScoreUnderTheVotesInAll() {
        show(ScoresState.Shown(ScoresFixtures.manyVotes, SubScreenFixtures.session))

        compose.onNodeWithText("1,081,549\u00A0votes").assertExists()
        val spoken =
            compose
                .onAllNodes(hasContentDescription("Score\u00A0", substring = true))
                .fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.ContentDescription].single() }
        assertEquals((10 downTo 1).map { "Score\u00A0$it" }, spoken.map { it.substringBefore(", ") })
        assertEquals("Score\u00A010, 373,989, 35\u00A0percent", spoken.first())
    }

    @Test
    fun aFailureOffersItsOwnRetryWordedForThisScreen() {
        show(ScoresState.Failed(DataError.Refused, SubScreenFixtures.session))
        compose.onNodeWithText("Could not load the scores").assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertEquals(listOf<ScoresEvent>(ScoresEvent.Retry), events)
    }

    @Test
    fun offlineSaysSoAndOffersRetry() {
        show(ScoresState.Failed(DataError.NoConnection, SubScreenFixtures.session))
        compose.onNodeWithText("No connection").assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun aTitleNobodyScoredAndATitleNotKnownSaySoWithoutBars() {
        show(ScoresFixtures.shown(FixtureAnime.premiereTimeSoon))
        compose.onNodeWithText("Nobody has scored this title yet").assertExists()
        assertEquals(0, compose.onAllNodes(hasContentDescription("Score\u00A0", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun aTitleTheServiceHasNoRecordOfSaysSo() {
        show(ScoresState.NotKnown(SubScreenFixtures.session))
        compose.onNodeWithText("The scores of this title are not known yet").assertExists()
    }

    @Test
    fun backIsTheTopBarsArrow() {
        show(ScoresState.Loading(SubScreenFixtures.session))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        assertEquals(listOf<ScoresEvent>(ScoresEvent.Back), events)
    }
}

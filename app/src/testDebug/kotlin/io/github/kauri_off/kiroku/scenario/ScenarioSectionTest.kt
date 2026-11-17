package io.github.kauri_off.kiroku.scenario

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// On the sandbox the panel can expire the access token now, and has no store to fail; on the demo it is the other way round.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class ScenarioSectionTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<ScenarioEvent>()

    private fun shown(sandbox: Boolean) =
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScenarioSection(ScenarioState.Ready(Scenario(), sandbox = sandbox), events::add)
            }
        }

    @Test
    fun `on the sandbox, Expire now asks for the access token to expire, and the store's choice is not offered`() {
        shown(sandbox = true)
        compose.onNodeWithText("Expire now").performScrollTo().performClick()
        assertEquals(listOf<ScenarioEvent>(ScenarioEvent.ExpireAccess), events)
        compose.onAllNodesWithText("The store on the device").assertCountEquals(0)
    }

    @Test
    fun `on the sandbox and on the demo, AniList can be made to fail or not answer`() {
        var sandbox by mutableStateOf(true)
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ScenarioSection(ScenarioState.Ready(Scenario(), sandbox = sandbox), events::add)
            }
        }
        compose.onNodeWithText("No answer").performScrollTo().performClick()
        sandbox = false
        compose.onNodeWithText("Fails").performScrollTo().performClick()
        assertEquals(
            listOf(
                ScenarioEvent.Change(Scenario(aniList = AniListFault.NoAnswer)),
                ScenarioEvent.Change(Scenario(aniList = AniListFault.Fails)),
            ),
            events,
        )
    }

    @Test
    fun `on the demo there is no access token to expire, and the store's choice is offered`() {
        shown(sandbox = false)
        compose.onAllNodesWithText("Expire now").assertCountEquals(0)
        compose.onAllNodesWithText("The store on the device").assertCountEquals(1)
    }
}

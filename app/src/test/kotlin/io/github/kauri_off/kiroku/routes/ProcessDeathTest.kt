package io.github.kauri_off.kiroku.routes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.demo.DemoUserLists
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.statistics.statisticsOf
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.profile.ProfileTags
import io.github.kauri_off.kiroku.shell.AppRoute
import io.github.kauri_off.kiroku.text.named
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.NumberFormat
import java.util.Locale

// Process death: the saved state comes back, every view model is new.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-w411dp-h891dp")
class ProcessDeathTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))

    private class Owner : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    @Test
    fun `the list's typed filter comes back after process death`() {
        var owner = Owner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        compose.onNodeWithContentDescription("Filter by title").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("kag")
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("kag")).assertExists()
        compose.onNodeWithText(FixtureAnime.exactToWatch.titles.romaji).assertExists()
    }

    @Test
    fun `the list's tab and an open edit sheet with a typed count come back after process death`() {
        var owner = Owner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        compose.onNode(hasText("Completed") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(FixtureAnime.prequel.titles.romaji).performTouchInput { longClick() }
        compose.waitForIdle()
        val field = compose.onNode(hasSetTextAction() and hasContentDescription("Episodes watched"))
        field.performTextReplacement("1")
        field.performTextReplacement("17")
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("17")).assertExists()
        compose.onNode(hasText("Completed") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertIsSelected()
    }

    @Test
    fun `another user's profile comes back after process death`() {
        var owner = Owner()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(graph) }
            }
        }
        val other = Fixtures.reference.otherUserName
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasText("Open another profile"))
        compose.onNodeWithText("Open another profile").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(other)
        compose.onNode(hasText("Open") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("View list").performScrollTo()
        compose.onNodeWithText(other).assertExists()
        owner.viewModelStore.clear()
        owner = Owner()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText(other).assertExists()
        compose.onNodeWithText("View list").performScrollTo()
        compose.onNodeWithText("Completed").assertExists()
    }

    @Test
    fun `another user's statistics page comes back after process death and makes the one read the list needs`() {
        var owner = Owner()
        var shown by mutableStateOf(graph)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                KirokuTheme(dark = false, dynamicColor = false) { AppRoute(shown) }
            }
        }
        val other = Fixtures.reference.otherUserName
        val statistics = statisticsOf(Fixtures.reference.otherUserList)
        val first = statistics.genres.first()
        val line = "${genreName(first.value)}:\u00A0${NumberFormat.getIntegerInstance(Locale.ENGLISH).format(first.count)}\u00A0titles"
        compose.onNode(hasText("Profile") and hasClickAction()).performClick()
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasText("Open another profile"))
        compose.onNodeWithText("Open another profile").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(other)
        compose.onNode(hasText("Open") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(ProfileTags.page).performScrollToNode(hasContentDescription("Genres", substring = true))
        compose.onNodeWithContentDescription("Genres", substring = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(line).assertExists()

        // A new process: every view model and the lists held in memory are gone.
        val restarted = TestGraph(Demo(Fixtures.reference, scope))
        owner.viewModelStore.clear()
        owner = Owner()
        shown = restarted
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithText("Genres").assertExists()
        compose.onNodeWithContentDescription(line).assertExists()
        val wholeRead = (Fixtures.reference.otherUserList.size + DemoUserLists.PART_SIZE - 1) / DemoUserLists.PART_SIZE
        assertEquals(wholeRead, restarted.demo.userLists.requests.size)
    }

    private fun genreName(genre: Genre): String = genre.named { ApplicationProvider.getApplicationContext<Context>().getString(it) }
}

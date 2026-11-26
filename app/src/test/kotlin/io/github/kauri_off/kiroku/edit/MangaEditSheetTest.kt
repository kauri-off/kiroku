package io.github.kauri_off.kiroku.edit

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.fetchRobolectricWindowRoots
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.screenshots.SystemBars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class MangaEditSheetTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val events = mutableListOf<MangaEditSheetEvent>()
    private var state by mutableStateOf(MangaEditFixtures.existing)

    private val chapters get() = countField("Chapters read")
    private val volumes get() = countField("Volumes read")

    private fun countField(label: String) = compose.onNodeWithContentDescription(label)

    private fun SemanticsNodeInteraction.enterCount(text: String) {
        performTextReplacement(text)
        performImeAction()
    }

    private val score get() = compose.onNodeWithContentDescription("Score")

    private fun setScore(value: Int) {
        score.performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
    }

    private fun scoreShows(value: String) = score.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))

    private val save get() = compose.onNodeWithText("Save")

    private fun sheet(initial: MangaEditSheetState = MangaEditFixtures.existing) {
        state = initial
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                MangaEditSheet(state) { event ->
                    events += event
                    state =
                        when (event) {
                            is MangaEditSheetEvent.Save, MangaEditSheetEvent.Retry ->
                                state.copy(submission = MangaEditSubmission.Saving)
                            else -> state.after(event)
                        }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun unknownStatusAndCountsStillAllowAnExplicitPartialEdit() {
        sheet(MangaEditFixtures.unknown)
        compose.onNodeWithText("Current status: Rereading").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(5).assertAll(isOff())
        scoreShows("Unknown")
        save.assertIsNotEnabled()
        chapters.enterCount("0")
        save.performClick()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        assertEquals(MangaEditSheetEvent.Save(MangaEditChanges(chaptersRead = 0)), events.last())
    }

    @Test
    fun steppersKeepAnUnknownCountSeparateFromZeroAndFromTheOtherCount() {
        sheet(MangaEditFixtures.unknown)
        val chapterMinus = compose.onNodeWithContentDescription("Minus one chapter")
        val chapterPlus = compose.onNodeWithContentDescription("Plus one chapter")
        val volumePlus = compose.onNodeWithContentDescription("Plus one volume")
        chapterMinus.assertIsNotEnabled()
        chapterPlus.performClick()
        chapters.assertTextContains("1")
        chapterMinus.performClick()
        chapters.assertTextContains("0")
        chapterMinus.assertIsNotEnabled()
        volumePlus.performClick()
        volumes.assertTextContains("1")
        volumes.enterCount("")
        save.performClick()
        assertEquals(MangaEditSheetEvent.Save(MangaEditChanges(chaptersRead = 0)), events.last())
    }

    @Test
    fun changingStatusCountsAndClearingScoreAreSavedTogether() {
        sheet()
        chapters.enterCount("28")
        volumes.enterCount("3")
        compose.onNodeWithText("On hold").performClick()
        setScore(0)
        scoreShows("Not scored")
        save.performClick()
        assertEquals(
            MangaEditSheetEvent.Save(MangaEditChanges(MangaEditStatus.OnHold, chaptersRead = 28, volumesRead = 3, score = 0)),
            events.last(),
        )
    }

    @Test
    fun restoringAnUnknownScoreKeepsTheChapterEdit() {
        sheet(MangaEditFixtures.unknown)
        chapters.enterCount("13")
        setScore(0)
        scoreShows("Not scored")
        setScore(UNKNOWN_SCORE_POSITION)
        scoreShows("Unknown")
        save.performClick()
        assertEquals(MangaEditSheetEvent.Save(MangaEditChanges(chaptersRead = 13)), events.last())
    }

    @Test
    fun restoringAnUnrecognizedStatusKeepsTheChapterEdit() {
        sheet(MangaEditFixtures.unknown)
        chapters.enterCount("13")
        compose.onNodeWithText("On hold").performClick()
        compose.onNodeWithText("Restore opening status").performClick()
        compose.onNodeWithText("Current status: Rereading").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertAll(isOff())
        save.performClick()
        assertEquals(MangaEditSheetEvent.Save(MangaEditChanges(chaptersRead = 13)), events.last())
    }

    @Test
    @OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
    fun keyboardStatusActivationKeepsFocusAndTabReachesTheFormActions() {
        sheet()
        val dropped = compose.onNode(hasText("Dropped") and isToggleable())
        compose.runOnIdle {
            val decor = fetchRobolectricWindowRoots().last().decorView
            val viewRoot = View::class.java.getMethod("getViewRootImpl").invoke(decor)
            ReflectionHelpers.callInstanceMethod<Boolean>(
                viewRoot,
                "ensureTouchModeLocally",
                ClassParameter.from(Boolean::class.java, false),
            )
        }
        dropped.performSemanticsAction(SemanticsActions.RequestFocus)
        dropped.assertIsFocused()
        dropped.performKeyInput { pressKey(Key.Enter) }
        dropped.assertIsFocused()
        assertEquals(MangaEditSheetEvent.StatusChosen(MangaEditStatus.Dropped), events.last())
        var focused = dropped
        for (control in listOf(
            compose.onNodeWithContentDescription("Minus one chapter"),
            chapters,
            compose.onNodeWithContentDescription("Plus one chapter"),
            compose.onNodeWithContentDescription("Minus one volume"),
            volumes,
            compose.onNodeWithContentDescription("Plus one volume"),
            score,
            save,
        )) {
            focused.performKeyInput { pressKey(Key.Tab) }
            control.assertIsFocused()
            focused = control
        }
    }

    @Test
    fun bothSteppersFitOnOneLineAtNormalTextSize() {
        sheet(MangaEditFixtures.paired)
        val chapter = chapters.fetchSemanticsNode().boundsInRoot
        val volume = volumes.fetchSemanticsNode().boundsInRoot
        assertEquals(chapter.top, volume.top, 0.5f)
        assertTrue(chapter.right < volume.left)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h640dp", fontScale = 2f)
    fun compactFormScrollsToTheVolumeStepperAndScoreSlider() {
        sheet()
        val chapter = chapters.fetchSemanticsNode().positionInRoot
        val volume = volumes.fetchSemanticsNode().positionInRoot
        assertTrue(chapter.y < volume.y)
        chapters.performScrollTo().enterCount("15")
        volumes.performScrollTo().performClick()
        applyImeInsets(visible = true)
        val focused = volumes.assertIsDisplayed().assertIsFocused().fetchSemanticsNode()
        assertEquals(focused.size.height.toFloat(), focused.boundsInRoot.height, 1f)
        save.assertDoesNotExist()
        volumes.enterCount("4")
        volumes.assertIsNotFocused()
        applyImeInsets(visible = false)
        save.assertIsDisplayed()
        score.performScrollTo().assertIsDisplayed()
        setScore(1)
        scoreShows("1 · Appalling")
        save.performClick()
        assertEquals(MangaEditSheetEvent.Save(MangaEditChanges(chaptersRead = 15, volumesRead = 4, score = 1)), events.last())
    }

    @OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
    private fun applyImeInsets(visible: Boolean) {
        compose.runOnIdle {
            val ime = WindowInsets.Type.ime()
            val density = compose.activity.resources.displayMetrics.density
            val height = if (visible) (SystemBars.KEYBOARD_DP * density).toInt() else 0
            fetchRobolectricWindowRoots().forEach { root ->
                val decor = root.decorView
                decor.dispatchApplyWindowInsets(
                    WindowInsets
                        .Builder(checkNotNull(decor.rootWindowInsets))
                        .setInsets(ime, Insets.of(0, 0, 0, height))
                        .setVisible(ime, visible)
                        .build(),
                )
            }
        }
    }

    @Test
    fun invalidInputShowsAnErrorAndDisablesSave() {
        sheet()
        chapters.enterCount("2147483648")
        compose.onNodeWithText("Enter a whole number from\u00A00 to\u00A02,147,483,647").assertIsDisplayed()
        save.assertIsNotEnabled()
        chapters.enterCount("13")
        compose.onNodeWithText("Enter a whole number", substring = true).assertDoesNotExist()
        save.performClick()
        assertEquals(MangaEditSheetEvent.Save(MangaEditChanges(chaptersRead = 13)), events.last())
    }

    @Test
    fun pendingShowsTheDraftAndSuppressesNewWrites() {
        sheet(MangaEditFixtures.saving)
        chapters.assertTextContains("13").assertIsNotEnabled()
        volumes.assertTextContains("3").assertIsNotEnabled()
        score.assertIsNotEnabled()
        assertSubmittedStatus()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        save.assertDoesNotExist()
        compose.onNodeWithText("Remove from list").assertDoesNotExist()
        assertEquals(emptyList<MangaEditSheetEvent>(), events)
    }

    private fun assertSubmittedStatus() {
        compose.onNodeWithText("Status in this edit: On hold").assertIsDisplayed()
        compose.onNode(hasText("Status in this edit: On hold") and hasClickAction()).assertDoesNotExist()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
    }

    @Test
    fun retryUsesItsOwnEventAndKeepsTheSubmittedDraft() {
        sheet(MangaEditFixtures.failed)
        compose.onNodeWithText("Could not save").assertIsDisplayed()
        assertSubmittedStatus()
        chapters.assertTextContains("13").assertIsNotEnabled()
        save.assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        assertEquals(listOf(MangaEditSheetEvent.Retry), events)
        assertEquals(MangaEditFixtures.saving, state)
    }

    @Test
    fun failureWithoutRetryHasNoWriteAction() {
        sheet(MangaEditFixtures.failed.copy(submission = MangaEditSubmission.Failed(canRetry = false)))
        compose.onNodeWithText("Could not save").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        save.assertDoesNotExist()
    }

    @Test
    fun removalFailureShowsTheCurrentStatusAndCanStartOver() {
        sheet(
            MangaEditFixtures.existing.copy(
                submission =
                    MangaEditSubmission.Failed(canRetry = true, message = R.string.manga_edit_could_not_remove, removal = true),
            ),
        )
        compose.onNodeWithText("Current status: Reading").assertIsDisplayed()
        compose.onNodeWithText("Status in this edit", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Start over").performClick()
        assertEquals(listOf(MangaEditSheetEvent.StartOver), events)
    }

    @Test
    fun dismissalAsksBeforeDiscardingAnUnsubmittedEdit() {
        sheet(MangaEditFixtures.changed)
        val dragHandle =
            compose.onNodeWithContentDescription(
                compose.activity.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description),
            )
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        chapters.assertTextContains("13")
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                MangaEditSheetEvent.DismissAsked,
                MangaEditSheetEvent.KeepEditing,
                MangaEditSheetEvent.DismissAsked,
                MangaEditSheetEvent.Discard,
                MangaEditSheetEvent.Dismissed,
            ),
            events,
        )
    }

    @Test
    @Config(qualifiers = "ru-w360dp-h640dp", fontScale = 2f)
    fun saveStaysInViewAtLargeText() {
        sheet(MangaEditFixtures.changed)
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
    }
}

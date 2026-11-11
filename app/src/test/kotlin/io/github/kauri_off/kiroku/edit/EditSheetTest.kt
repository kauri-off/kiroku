package io.github.kauri_off.kiroku.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp")
class EditSheetTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val today = LocalDate.of(2026, 1, 14)
    private val saved =
        ListValues(ListStatus.Watching, 12, score = 8, startDate = PartialDate.Day(LocalDate.of(2025, 10, 12)), finishDate = null)
    private val events = mutableListOf<EditSheetEvent>()
    private var state by mutableStateOf(EditSheetState.of(saved, total = 24, today))

    private fun sheet(initial: EditSheetState = state) {
        state = initial
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                EditSheet(state) {
                    events += it
                    state = state.after(it)
                }
            }
        }
        compose.waitForIdle()
    }

    private val save get() = compose.onNodeWithText("Save")
    private val field get() = compose.onNodeWithContentDescription("Episodes watched")
    private val dragHandle
        get() =
            compose.onNodeWithContentDescription(
                compose.activity.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_drag_handle_description),
            )

    private fun sheetIsOpen() = compose.onNodeWithText("Save").assertExists()

    @Test
    fun editsAreEventsAndOnlySaveAsksForAWrite() {
        sheet()
        compose.onNodeWithText("On hold").performClick()
        compose.onNodeWithContentDescription("Plus one episode").performClick()
        compose.onNodeWithContentDescription("Clear the start date").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                EditSheetEvent.StatusChosen(ListStatus.OnHold),
                EditSheetEvent.WatchedChanged(13),
                EditSheetEvent.DateCleared(DateField.Start),
            ),
            events,
        )
        save.performClick()
        compose.waitForIdle()
        assertEquals(EditSheetEvent.SaveClicked, events[3])
    }

    @Test
    fun aTypedCountIsAppliedBeforeSave() {
        sheet()
        field.performTextReplacement("17")
        compose.waitForIdle()
        assertEquals(emptyList<EditSheetEvent>(), events)
        save.performClick()
        compose.waitForIdle()
        assertEquals(listOf(EditSheetEvent.WatchedChanged(17), EditSheetEvent.SaveClicked), events.take(2))
    }

    @Test
    fun aCountAboveTheTotalCannotBeSaved() {
        sheet()
        field.performTextReplacement("26")
        compose.waitForIdle()
        save.assertIsNotEnabled()
        field.performTextReplacement("24")
        compose.waitForIdle()
        save.assertIsEnabled()
    }

    @Test
    fun withoutChangesTheSheetSimplyCloses() {
        sheet()
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        assertEquals(listOf(EditSheetEvent.Dismissed), events)
    }

    @Test
    fun aSwipeWithChangesAsksAndKeepsTheSheet() {
        sheet()
        compose.onNodeWithText("Dropped").performClick()
        compose.onNodeWithText("Dropped").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(EditSheetEvent.DismissAsked, events.last())
        assertFalse(EditSheetEvent.Dismissed in events)
        sheetIsOpen()
        compose.onNodeWithText("Discard changes?").assertExists()
    }

    @Test
    fun backWithChangesAsksAndKeepsTheSheet() {
        sheet()
        compose.onNodeWithText("Dropped").performClick()
        compose.waitForIdle()
        Espresso.pressBack()
        compose.waitForIdle()
        assertEquals(EditSheetEvent.DismissAsked, events.last())
        assertFalse(EditSheetEvent.Dismissed in events)
        sheetIsOpen()
    }

    @Test
    fun anUnconfirmedCountIsAChange() {
        sheet()
        field.performTextReplacement("17")
        compose.waitForIdle()
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        assertEquals(listOf(EditSheetEvent.DismissAsked), events)
        sheetIsOpen()
    }

    @Test
    fun keepEditingKeepsTheValuesAndDiscardCloses() {
        sheet()
        compose.onNodeWithText("Dropped").performClick()
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        compose.onNodeWithText("Keep editing").performClick()
        compose.waitForIdle()
        assertEquals(ListStatus.Dropped, state.draft.status)
        sheetIsOpen()
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        assertEquals(EditSheetEvent.DiscardConfirmed, events[events.size - 2])
        assertEquals(EditSheetEvent.Dismissed, events.last())
    }

    @Test
    fun removeAsksForConfirmation() {
        sheet()
        compose.onNodeWithText("Remove from list").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove from list?").assertExists()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(EditSheetEvent.RemoveClicked, EditSheetEvent.RemoveConfirmed, EditSheetEvent.Dismissed),
            events,
        )
    }

    @Test
    fun aTitleNotOnTheListHasNothingToRemove() {
        sheet(EditSheetState.of(null, total = 12, today))
        sheetIsOpen()
        compose.onNodeWithText("Remove from list").assertDoesNotExist()
    }

    @Test
    fun aDateFieldOpensThePicker() {
        sheet()
        compose.onNodeWithText("Finish date").performClick()
        compose.waitForIdle()
        assertEquals(listOf(EditSheetEvent.DateOpened(DateField.Finish)), events)
        compose.onNodeWithText("OK").assertExists()
    }
}

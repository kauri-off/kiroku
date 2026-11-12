package io.github.kauri_off.kiroku.edit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    private val opened = EditSheetState.of(TITLE, saved, total = 24, malEpisodes = 24, today)
    private var state by mutableStateOf(opened)

    private val content: @Composable () -> Unit = {
        KirokuTheme(dark = false, dynamicColor = false) {
            EditSheet(state) {
                events += it
                state = state.after(it)
            }
        }
    }

    private fun sheet(initial: EditSheetState = opened) {
        state = initial
        compose.setContent(content)
        compose.waitForIdle()
    }

    // Save writes the draft, so the draft must be what the sheet shows when Save is tapped.
    private fun assertTheSheetShows(values: ListValues) {
        compose.onNode(hasText(statusNames.getValue(values.status)) and isToggleable()).assertIsOn()
        field.assertTextContains(values.watchedEpisodes.toString(), substring = true)
        dateField("Start date").assertTextContains(values.startDate.shown(), substring = true)
        dateField("Finish date").assertTextContains(values.finishDate.shown(), substring = true)
    }

    private fun PartialDate?.shown() = (this as? PartialDate.Day)?.date?.let { DAY.format(it) } ?: ""

    private fun dateField(label: String) = compose.onNode(hasText(label) and hasClickAction())

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
    @Config(qualifiers = "ru-w360dp-h640dp", fontScale = 1.3f)
    fun saveIsOnScreenWithoutScrollingOnASmallScreenWithLargeText() {
        sheet()
        compose.onNodeWithText("Сохранить").assertIsDisplayed()
        compose.onNodeWithText("Удалить из списка").assertIsDisplayed()
    }

    @Test
    fun theSheetNamesTheTitle() {
        sheet()
        compose.onNodeWithText(TITLE).assertExists()
    }

    @Test
    fun aTypedCountTakesEffectAsItIsTyped() {
        sheet()
        field.performTextReplacement("17")
        compose.waitForIdle()
        assertEquals(listOf(EditSheetEvent.WatchedTyped("17")), events)
        assertEquals(17, state.draft.watchedEpisodes)
        save.performClick()
        compose.waitForIdle()
        assertEquals(EditSheetEvent.SaveClicked, events[1])
        assertEquals(saved.copy(watchedEpisodes = 17), state.draft)
    }

    @Test
    fun aTypedCountThenAStatusSavesWhatTheSheetShows() {
        sheet()
        field.performTextReplacement("24")
        compose.onNodeWithText("Dropped").performClick()
        compose.waitForIdle()
        val shown = saved.copy(status = ListStatus.Dropped, watchedEpisodes = 24, finishDate = PartialDate.Day(today))
        assertTheSheetShows(shown)
        save.performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(EditSheetEvent.WatchedTyped("24"), EditSheetEvent.StatusChosen(ListStatus.Dropped), EditSheetEvent.SaveClicked),
            events.filter { it != EditSheetEvent.TypingEnded }.take(3),
        )
        assertTrue(state.closing)
        assertEquals(shown, state.draft)
    }

    @Test
    fun aTypedCountThenAClearedDateSavesWhatTheSheetShows() {
        sheet()
        field.performTextReplacement("24")
        compose.onNodeWithContentDescription("Clear the finish date").performClick()
        compose.waitForIdle()
        val shown = saved.copy(status = ListStatus.Completed, watchedEpisodes = 24, finishDate = null)
        assertTheSheetShows(shown)
        save.performClick()
        compose.waitForIdle()
        assertTrue(state.closing)
        assertEquals(shown, state.draft)
    }

    @Test
    fun aRestoredSheetSavesTheTypedCountWithoutFocus() {
        state = opened
        val restoration = StateRestorationTester(compose)
        restoration.setContent(content)
        field.performTextReplacement("17")
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        field.assertIsNotFocused()
        field.assertTextContains("17", substring = true)
        save.performClick()
        compose.waitForIdle()
        assertTrue(state.closing)
        assertEquals(17, state.draft.watchedEpisodes)
    }

    @Test
    fun aRestoredTypedCountIsWhatSaveWrites() {
        sheet(opened.after(EditSheetEvent.WatchedTyped("17")))
        field.assertIsNotFocused()
        save.performClick()
        compose.waitForIdle()
        assertEquals(EditSheetEvent.SaveClicked, events.first())
        assertTrue(state.closing)
        assertEquals(saved.copy(watchedEpisodes = 17), state.draft)
    }

    // One replacement per keystroke, as a real Backspace makes them.
    @Test
    fun clearingTheFieldAndLeavingItReturnsToTheCountFromBeforeTyping() {
        sheet()
        field.performClick()
        field.performTextReplacement("1")
        field.performTextReplacement("")
        compose.waitForIdle()
        save.assertIsNotEnabled()
        field.performImeAction()
        compose.waitForIdle()
        field.assertTextContains("12", substring = true)
        save.assertIsEnabled()
        assertEquals(opened, state)
    }

    @Test
    fun clearingTheFieldTakesBackTheStatusAndDateAKeystrokeChanged() {
        sheet(EditSheetState.of(TITLE, null, total = 24, malEpisodes = 24, today))
        field.performClick()
        listOf("", "2", "26", "2", "").forEach { field.performTextReplacement(it) }
        field.performImeAction()
        compose.waitForIdle()
        assertTheSheetShows(EditSheetState.newEntry)
        assertFalse(state.hasChanges)
    }

    @Test
    fun aDisabledSaveWritesNothing() {
        val empty = opened.after(EditSheetEvent.WatchedTyped(""))
        assertFalse(empty.after(EditSheetEvent.SaveClicked).closing)
    }

    @Test
    fun aCountAboveTheTotalIsSavedAsShown() {
        sheet()
        field.performTextReplacement("2")
        field.performTextReplacement("26")
        compose.waitForIdle()
        val shown = saved.copy(status = ListStatus.Completed, watchedEpisodes = 26, finishDate = PartialDate.Day(today))
        assertTheSheetShows(shown)
        compose.onNodeWithContentDescription("Plus one episode").assertIsEnabled()
        save.performClick()
        compose.waitForIdle()
        assertTrue(state.closing)
        assertEquals(shown, state.draft)
    }

    @Test
    fun aTapOnAChipOrTheSliderEndsTyping() {
        sheet()
        field.performClick()
        field.performTextReplacement("13")
        compose.onNodeWithText("On hold").performClick()
        compose.waitForIdle()
        field.assertIsNotFocused()
        assertEquals(null, state.typed)
        field.performClick()
        field.performTextReplacement("14")
        compose.onNodeWithContentDescription("Score").performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        compose.waitForIdle()
        field.assertIsNotFocused()
        assertEquals(saved.copy(status = ListStatus.OnHold, watchedEpisodes = 14, score = 9), state.draft)
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
    fun aChangedCountBeingTypedIsAChange() {
        sheet()
        field.performTextReplacement("17")
        compose.waitForIdle()
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        assertEquals(EditSheetEvent.DismissAsked, events.last())
        sheetIsOpen()
    }

    @Test
    fun anEmptiedFieldWithNothingChangedSimplyCloses() {
        sheet()
        field.performTextReplacement("")
        compose.waitForIdle()
        dragHandle.performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        assertEquals(EditSheetEvent.Dismissed, events.last())
        assertFalse(EditSheetEvent.DismissAsked in events)
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
        sheet(EditSheetState.of(TITLE, null, total = 12, malEpisodes = 12, today))
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

    @Test
    fun aScreenReaderOpensThePickerFromADateField() {
        sheet()
        dateField("Finish date").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(EditSheetEvent.DateOpened(DateField.Finish)), events)
    }

    @Test
    fun aDateMalHoldsAsAYearOrAMonthShowsAsSuch() {
        sheet(
            opened.copy(
                draft =
                    saved.copy(
                        startDate = PartialDate.OnlyYear(Year.of(2025)),
                        finishDate = PartialDate.Month(YearMonth.of(2026, 1)),
                    ),
            ),
        )
        dateField("Start date").assertTextContains("2025")
        dateField("Finish date").assertTextContains("January 2026")
    }

    @Test
    fun everyStatusIsAnnouncedAsARadioButton() {
        sheet()
        for (name in statusNames.values) {
            compose
                .onNode(
                    hasText(name) and isToggleable(),
                ).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
    }

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp")
    fun theStatusesFillEvenRowsWithAGapBetweenThem() = assertStatusRows()

    @Test
    @Config(qualifiers = "ru-w411dp-h891dp", fontScale = 2f)
    fun theStatusesFillEvenRowsWithAGapBetweenThemAtLargeText() = assertStatusRows()

    private fun assertStatusRows() {
        sheet()
        val buttons = compose.onAllNodes(isToggleable()).fetchSemanticsNodes().map { it.boundsInRoot }
        val rows =
            buttons
                .groupBy { it.top }
                .toSortedMap()
                .values
                .toList()
        assertTrue("${rows.size} rows", rows.size > 1)
        val density = compose.activity.resources.displayMetrics.density
        rows.zipWithNext().forEach { (above, below) ->
            assertTrue("rows ${above.first().bottom} and ${below.first().top}", below.first().top - above.first().bottom >= 8 * density - 1)
        }
        val left = rows.minOf { row -> row.minOf { it.left } }
        val right = rows.maxOf { row -> row.maxOf { it.right } }
        rows.forEach { row ->
            assertEquals(left, row.minOf { it.left }, 1f)
            assertEquals(right, row.maxOf { it.right }, 1f)
        }
    }

    @Test
    fun rowsAreTheFewestThatFitAndAsEvenAsTheyCanBe() {
        val widths = listOf(84, 137, 124, 102, 97).map { it.dp }
        assertEquals(listOf(listOf(0, 1, 2, 3, 4)), balancedRows(widths, 600.dp, 2.dp))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3, 4)), balancedRows(widths, 379.dp, 2.dp))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), balancedRows(widths, 240.dp, 2.dp))
        assertEquals(widths.indices.map { listOf(it) }, balancedRows(widths, 100.dp, 2.dp))
    }

    private companion object {
        const val TITLE = "Kagerou Toshokan 2nd Season"
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, y", Locale.ENGLISH)
        val statusNames =
            mapOf(
                ListStatus.Watching to "Watching",
                ListStatus.Planned to "Planned",
                ListStatus.Completed to "Completed",
                ListStatus.OnHold to "On hold",
                ListStatus.Dropped to "Dropped",
            )
    }
}

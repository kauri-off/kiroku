package io.github.kauri_off.kiroku.design

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import io.github.kauri_off.kiroku.design.component.WindowWithoutAutofill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// A sheet or dialog is a window apart; while it is shown, none of its fields is offered to autofill.
@RunWith(RobolectricTestRunner::class)
class WindowWithoutAutofillTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @Test
    fun `a dialog's window is excluded from autofill while it is shown, and given back after`() {
        var shown by mutableStateOf(true)
        lateinit var screen: View
        lateinit var dialog: View
        compose.setContent {
            screen = LocalView.current.rootView
            if (shown) {
                Dialog(onDismissRequest = {}) {
                    WindowWithoutAutofill()
                    dialog = LocalView.current.rootView
                }
            }
        }
        compose.waitForIdle()
        assertNotSame(screen, dialog)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, dialog.importantForAutofill)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, screen.importantForAutofill)
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, dialog.importantForAutofill)
    }
}

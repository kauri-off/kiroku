package io.github.kauri_off.kiroku.screenshots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityCheckTest {
    @Test
    fun reportsSmallTouchTarget() {
        val problems =
            Shot
                .launch(Variant.EnLight) {
                    Surface {
                        Box(Modifier.size(24.dp).semantics { contentDescription = "small" }.clickable {})
                    }
                }.use { it.accessibilityProblems() }
        assertTrue(problems.toString(), problems.any { "TouchTargetSizeCheck" in it })
    }

    @Test
    fun reportsUnlabeledControl() {
        val problems =
            Shot
                .launch(Variant.EnLight) {
                    Surface { Box(Modifier.size(56.dp).clickable {}) }
                }.use { it.accessibilityProblems() }
        assertTrue(problems.toString(), problems.any { "SpeakableTextPresentCheck" in it })
    }

    @Test
    fun acceptsStockButton() {
        val problems =
            Shot
                .launch(Variant.EnLight) {
                    Surface { Column { Button(onClick = {}) { Text("Save") } } }
                }.use { it.accessibilityProblems() }
        assertEquals(emptyList<String>(), problems)
    }
}

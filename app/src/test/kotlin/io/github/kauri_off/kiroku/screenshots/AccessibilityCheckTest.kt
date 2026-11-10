package io.github.kauri_off.kiroku.screenshots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.probe.CentredSmallTarget
import io.github.kauri_off.kiroku.probe.DialogProbe
import io.github.kauri_off.kiroku.probe.LongListEndingInSmallTarget
import io.github.kauri_off.kiroku.probe.SheetProbe
import io.github.kauri_off.kiroku.probe.StockControls
import io.github.kauri_off.kiroku.probe.StripProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityCheckTest {
    @get:Rule
    val output = TemporaryFolder()

    private fun problems(content: @androidx.compose.runtime.Composable () -> Unit) =
        Shot.launch(Variant.EnLight, content = content).use { it.accessibilityProblems() }

    private fun rendered(content: @androidx.compose.runtime.Composable () -> Unit) =
        renderScreen("probe", listOf(contentCase(content = content)), output.root, { listOf(Variant.EnLight) }) { _, _ -> }

    private fun List<String>.reportsSmall(label: String) = any { it.startsWith("TouchTargetSize: '$label'") }

    @Test
    fun reportsSmallTargetWithRoomAroundIt() = assertTrue(problems { CentredSmallTarget() }.reportsSmall("centred"))

    @Test
    fun reportsSmallTargetInDialog() = assertTrue(problems { DialogProbe(smallTarget = true) }.reportsSmall("in a dialog"))

    @Test
    fun reportsSmallTargetInSheet() =
        assertTrue(problems { SheetProbe(rows = 3, rowsBehind = 40, smallTarget = true) }.reportsSmall("in a sheet"))

    @Test
    fun reportsSmallTargetBelowTheFold() = assertTrue(rendered { LongListEndingInSmallTarget() }.any { "below the fold" in it })

    @Test
    fun reportsSmallTargetInStripBeyondTheScreen() =
        assertTrue(rendered { StripProbe(smallTargetInLastCard = true) }.any { "in the last card" in it })

    @Test
    fun reportsUnlabeledControl() {
        val problems = problems { Surface { Box(Modifier.size(56.dp).clickable {}) } }
        assertTrue(problems.toString(), problems.any { "SpeakableTextPresentCheck" in it })
    }

    @Test
    fun acceptsStockControls() = assertEquals(emptyList<String>(), problems { StockControls() })

    @Test
    fun acceptsSheetDragHandle() = assertEquals(emptyList<String>(), problems { SheetProbe(rows = 3, rowsBehind = 0) })
}

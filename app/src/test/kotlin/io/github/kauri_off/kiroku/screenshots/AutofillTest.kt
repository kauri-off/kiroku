package io.github.kauri_off.kiroku.screenshots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

// No text field of any screen is offered to autofill or to a password manager: every case of every registry is checked.
@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutofillTest {
    @Test
    fun noTextFieldIsOfferedToAutofill() {
        val seen = mutableSetOf<String>()
        val offered = mutableListOf<String>()
        for (registry in registryClasses().map { it.getDeclaredConstructor().newInstance() as RegistryBase }) {
            for (case in registry.shape().cases) {
                val variant = Variant.EnLight
                val heightDp = case.window?.heightDp ?: variant.heightDp
                val widthDp = case.window?.widthDp ?: variant.widthDp
                Shot.launch(variant, heightDp, case.keyboard, widthDp, case.content).use { shot ->
                    for ((label, isOffered) in shot.textFields()) {
                        seen += "${registry.screen}/${case.name}: $label"
                        if (isOffered) offered += "${registry.screen}/${case.name}: '$label'"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), offered)
        for (field in listOf(
            "edit/existing: Episodes watched",
            "edit/existing: Start date",
            "edit/existing: Finish date",
            "list/filtering",
        )) {
            assertTrue("$field is among the fields checked: $seen", seen.any { it.startsWith(field) })
        }
    }
}

package io.github.kauri_off.kiroku.design.component

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics

// Every text field takes this first in its modifier, so none is offered to autofill or to a password manager.
fun Modifier.withoutAutofill(): Modifier = semantics { contentDataType = ContentDataType.None }

// A sheet or dialog is a window of its own: while this is composed in one, none of its fields is offered to autofill.
@Composable
fun WindowWithoutAutofill() {
    val root = LocalView.current.rootView
    DisposableEffect(root) {
        val before = root.importantForAutofill
        root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { root.importantForAutofill = before }
    }
}

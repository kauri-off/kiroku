package io.github.kauri_off.kiroku.design.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.semantics

// Every text field of the app takes this: none is offered to autofill or to a password manager. It must be the
// field's own modifier, outside the field's semantics, which would otherwise make it fillable text.
fun Modifier.withoutAutofill(): Modifier = semantics { contentDataType = ContentDataType.None }

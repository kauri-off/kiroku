package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing

// A button with nothing typed reports a step to [onStep], so quick taps each count before [watched] catches up.
@Composable
fun EpisodeStepper(
    watched: Int,
    total: Int?,
    onWatchedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onStep: (Int) -> Unit = { onWatchedChange(watched + it) },
) = EpisodeStepper(watched, total, onWatchedChange, modifier, enabled, typed = null, onStep = onStep)

// [typed] stands for text the user has typed but not yet confirmed.
@Composable
internal fun EpisodeStepper(
    watched: Int,
    total: Int?,
    onWatchedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    typed: String?,
    onStep: (Int) -> Unit = { onWatchedChange(watched + it) },
) {
    var draft by rememberSaveable { mutableStateOf(typed) }
    var focused by remember { mutableStateOf(false) }
    var given by remember { mutableStateOf(watched) }
    if (given != watched) {
        given = watched
        if (!focused) draft = null
    }
    val focusManager = LocalFocusManager.current

    fun commit() {
        val count = draft?.let { typedCount(it, watched) }
        draft = null
        if (count != null) onWatchedChange(count)
    }

    EpisodeStepperLayout(
        text = draft ?: watched.toString(),
        current = draft?.toIntOrNull() ?: watched,
        total = total,
        onTextChange = { draft = it },
        // Leaving the field can resend its last text, so the commit comes after it.
        onDone = {
            focusManager.clearFocus()
            commit()
        },
        onFocusChange = { isFocused ->
            focused = isFocused
            if (!isFocused) commit()
        },
        onStep = { count ->
            val typedCount = draft?.let { typedCount(it, watched) }
            draft = null
            if (typedCount != null) onWatchedChange(count) else onStep(count - watched)
        },
        modifier = modifier,
        enabled = enabled,
    )
}

// The stepper without state of its own. [current] is the count the buttons step from; [onTextChange] hears only text
// a count field takes, [onFocusChange] only real changes, [onStep] the new count.
@Composable
internal fun EpisodeStepperLayout(
    text: String,
    current: Int,
    total: Int?,
    onTextChange: (String) -> Unit,
    onDone: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fieldModifier: Modifier = Modifier,
) {
    val minus = stringResource(R.string.design_episode_minus)
    val plus = stringResource(R.string.design_episode_plus)
    val fieldLabel = stringResource(R.string.design_episodes_watched)
    var focused by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        FilledTonalIconButton(onClick = { onStep(current - 1) }, shapes = IconButtonDefaults.shapes(), enabled = enabled && current > 0) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = minus)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { edit -> episodeText(edit)?.let(onTextChange) },
            enabled = enabled,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
            visualTransformation =
                totalSuffix(
                    total,
                    if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else OutlinedTextFieldDefaults.colors().disabledTextColor,
                ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier =
                fieldModifier
                    .withoutAutofill()
                    .weight(1f, fill = false)
                    .width(fieldWidth(maxOf(text.length, current.toString().length), total))
                    .onFocusChanged {
                        if (it.isFocused != focused) {
                            focused = it.isFocused
                            onFocusChange(it.isFocused)
                        }
                    }.semantics { contentDescription = fieldLabel },
        )
        FilledTonalIconButton(
            onClick = { onStep(current + 1) },
            shapes = IconButtonDefaults.shapes(),
            enabled =
                enabled && current < MAX_COUNT,
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = plus)
        }
    }
}

// The room a step button's touch target leaves beside its container, which a caller lets reach into its padding.
val stepButtonMargin: Dp
    @Composable get() = (LocalMinimumInteractiveComponentSize.current - IconButtonDefaults.smallContainerSize().width) / 2

// The count confirming [text] applies, or null when it keeps [watched]: an empty or unchanged field.
internal fun typedCount(
    text: String,
    watched: Int,
): Int? = text.toIntOrNull()?.takeIf { it != watched }

// The field's text after an edit, or null when the edit is not taken: anything but digits, or more than
// [MAX_DIGITS] of them.
internal fun episodeText(edit: String): String? {
    val text = edit.filterNot(Char::isWhitespace)
    if (text.any { it !in '0'..'9' }) return null
    return text.takeIf { it.length <= MAX_DIGITS }
}

@Composable
private fun totalSuffix(
    total: Int?,
    color: Color,
): VisualTransformation {
    if (total == null) return VisualTransformation.None
    val suffix = stringResource(R.string.design_episode_total, total)
    return remember(suffix, color) {
        VisualTransformation { input ->
            val shown =
                buildAnnotatedString {
                    append(input)
                    withStyle(SpanStyle(color = color)) { append(suffix) }
                }
            TransformedText(shown, SuffixMapping(input.length))
        }
    }
}

private class SuffixMapping(
    private val length: Int,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = offset

    override fun transformedToOriginal(offset: Int): Int = offset.coerceAtMost(length)
}

// Wide enough for [digits] digits with the total at the current font scale, and for the total's own digits.
@Composable
private fun fieldWidth(
    digits: Int,
    total: Int?,
): Dp {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val shown = maxOf(digits, total?.toString()?.length ?: UNKNOWN_TOTAL_DIGITS)
    val widest = AnnotatedString(ZERO.toString().repeat(shown) + (total?.let { stringResource(R.string.design_episode_total, it) } ?: ""))
    val width = measurer.measure(widest, style, softWrap = false).size.width
    return with(LocalDensity.current) { width.toDp() } + FieldPadding * 2 + CursorRoom
}

private const val UNKNOWN_TOTAL_DIGITS = 4

// Input sanity, not a ceiling: no title has this many episodes.
private const val MAX_DIGITS = 5
internal const val MAX_COUNT = 99_999
private const val ZERO = '0'

// A text field's own start and end padding, and room for the cursor after the last digit.
private val FieldPadding = Spacing.l
private val CursorRoom = Spacing.xs

package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
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
import androidx.compose.ui.unit.dp
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.theme.Spacing

// Minus, the watched count as a numeric field with "/ total", plus. A typed count is applied when the user
// confirms it or leaves the field; a count above the total is refused with an inline error and not applied.
@Composable
fun EpisodeStepper(
    watched: Int,
    total: Int?,
    onWatchedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) = EpisodeStepper(watched, total, onWatchedChange, modifier, enabled, draft = null)

// [draft] stands for text the user has typed but not yet confirmed.
@Composable
internal fun EpisodeStepper(
    watched: Int,
    total: Int?,
    onWatchedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    draft: String?,
) {
    var text by remember(watched) { mutableStateOf(draft ?: watched.toString()) }
    var applied by remember(watched) { mutableIntStateOf(watched) }
    var refused by remember(watched, total) { mutableStateOf(draft != null && episodeInput(draft, watched, total) is EpisodeInput.TooMany) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun commit() {
        when (val input = episodeInput(text, applied, total)) {
            is EpisodeInput.Apply -> {
                refused = false
                applied = input.value
                text = input.value.toString()
                onWatchedChange(input.value)
            }
            EpisodeInput.Keep -> {
                refused = false
                text = applied.toString()
            }
            is EpisodeInput.TooMany -> refused = true
        }
    }

    val minus = stringResource(R.string.design_episode_minus)
    val plus = stringResource(R.string.design_episode_plus)
    val fieldLabel = stringResource(R.string.design_episodes_watched)
    val refusal = total?.let { pluralStringResource(R.plurals.design_episode_too_many, it, it) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            IconButton(onClick = { onWatchedChange(watched - 1) }, enabled = enabled && watched > 0) {
                Icon(painterResource(R.drawable.ic_remove), contentDescription = minus)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = filterEpisodeText(it, total) },
                enabled = enabled,
                singleLine = true,
                isError = refused,
                textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                visualTransformation =
                    totalSuffix(
                        total,
                        if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else OutlinedTextFieldDefaults.colors().disabledTextColor,
                    ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions =
                    KeyboardActions(onDone = {
                        commit()
                        if (!refused) focusManager.clearFocus()
                    }),
                modifier =
                    Modifier
                        .width(fieldWidth(total))
                        .onFocusChanged {
                            if (focused && !it.isFocused) commit()
                            focused = it.isFocused
                        }.semantics {
                            contentDescription = fieldLabel
                            if (refused && refusal != null) error(refusal)
                        },
            )
            IconButton(onClick = { onWatchedChange(watched + 1) }, enabled = enabled && (total == null || watched < total)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = plus)
            }
        }
        if (refused && refusal != null) {
            Text(
                refusal,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal sealed interface EpisodeInput {
    data class Apply(
        val value: Int,
    ) : EpisodeInput

    data object Keep : EpisodeInput

    data class TooMany(
        val total: Int,
    ) : EpisodeInput
}

// What confirming [text] does: an empty or unchanged field keeps the count, a count above the total is refused.
internal fun episodeInput(
    text: String,
    watched: Int,
    total: Int?,
): EpisodeInput {
    val value = text.toIntOrNull() ?: return EpisodeInput.Keep
    return when {
        total != null && value > total -> EpisodeInput.TooMany(total)
        value == watched -> EpisodeInput.Keep
        else -> EpisodeInput.Apply(value)
    }
}

internal fun filterEpisodeText(
    raw: String,
    total: Int?,
): String = raw.filter { it in '0'..'9' }.take(maxDigits(total))

private fun maxDigits(total: Int?): Int = total?.toString()?.length ?: UNKNOWN_TOTAL_DIGITS

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

// Wide enough for the longest count with its total at the current font scale, and no wider.
@Composable
private fun fieldWidth(total: Int?): Dp {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val widest =
        AnnotatedString(
            ZERO.repeat(maxDigits(total)) + (total?.let { stringResource(R.string.design_episode_total, it) } ?: ""),
        )
    val width = measurer.measure(widest, style, softWrap = false).size.width
    return with(LocalDensity.current) { width.toDp() } + FieldPadding * 2 + CursorRoom
}

private const val UNKNOWN_TOTAL_DIGITS = 4
private const val ZERO = "0"
private val FieldPadding = 16.dp
private val CursorRoom = 4.dp

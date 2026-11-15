package io.github.kauri_off.kiroku.design.component

import android.content.ClipData
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import io.github.kauri_off.kiroku.notShown

// An anime title. The drawn text may break after each hyphen; everything that reads the node gets [title] unchanged.
@Composable
fun TitleText(
    title: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val drawn = remember { DrawnTitle() }
    Text(
        breakableTitle(title),
        modifier =
            modifier.clearAndSetSemantics {
                text = AnnotatedString(title)
                this[DrawnTitleKey] = drawn
            },
        color = color,
        style = style,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { drawn.layout = it },
    )
}

// The platform's text selection over [content]'s titles; a copy of one is the title as written, without the drawn breaks.
@Composable
fun TitleSelection(content: @Composable () -> Unit) {
    val platform = LocalClipboard.current
    val unbroken = remember(platform) { UnbrokenClipboard(platform) }
    CompositionLocalProvider(LocalClipboard provides unbroken) { SelectionContainer(content = content) }
}

private class UnbrokenClipboard(
    private val platform: Clipboard,
) : Clipboard by platform {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) = platform.setClipEntry(clipEntry?.unbroken())

    private fun ClipEntry.unbroken(): ClipEntry {
        val copied = clipData.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text ?: return this
        return ClipData.newPlainText(clipData.description.label, copied.toString().replace(BREAK.toString(), "")).toClipEntry()
    }
}

// The text as drawn, with a break opportunity after each hyphen.
internal fun breakableTitle(title: String): String =
    buildString {
        for (c in title) {
            append(c)
            if (c == HYPHEN) append(BREAK)
        }
    }

// The drawn layout of a title or a scored line, for tests; accessibility services do not read custom keys.
internal class DrawnTitle {
    var layout: TextLayoutResult? = null
}

internal val DrawnTitleKey = SemanticsPropertyKey<DrawnTitle>(notShown("DrawnTitle"))

private const val HYPHEN = '-'
private const val BREAK = '\u200B'

package io.github.kauri_off.kiroku.design.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

// A part of a page: [gap] is the room from the last line of the part above to its own first line; [lead] and [trail]
// are the blank room the part carries itself above its first line and under its last one.
class PageBlock(
    val key: Any,
    val gap: Dp,
    val lead: Dp = Dp.Hairline,
    val trail: Dp = Dp.Hairline,
    val content: @Composable () -> Unit,
)

// The parts of a page, each [PageBlock.gap] under the one above, measured line to line; the first one's first line is
// where the page starts.
fun LazyListScope.pageBlocks(blocks: List<PageBlock>) {
    blocks.forEachIndexed { index, block ->
        val space = if (index == 0) -block.lead else block.gap - blocks[index - 1].trail - block.lead
        item(key = block.key) { Spaced(space, block.content) }
    }
}

// [content] [space] under what is above it; a space below zero lays it over the blank room above its own first line.
@Composable
fun Spaced(
    space: Dp,
    content: @Composable () -> Unit,
) {
    if (space >= Dp.Hairline) {
        Column {
            Spacer(Modifier.height(space))
            content()
        }
    } else {
        Column(Modifier.trimTop(-space)) { content() }
    }
}

// The blank room above a section header's title: the header is a touch target tall with its title centred.
@Composable
fun headingRoom(): Dp {
    val title =
        with(LocalDensity.current) {
            MaterialTheme.typography.titleMedium.lineHeight
                .toDp()
        }
    return maxOf((LocalMinimumInteractiveComponentSize.current - title) / 2, Dp.Hairline)
}

// Leaves out the top [amount] of the content's own room, so it sits as close to the block above as plain text does.
fun Modifier.trimTop(amount: Dp) =
    layout { measurable, constraints ->
        val trim = amount.roundToPx()
        val placeable = measurable.measure(constraints)
        layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, -trim) }
    }

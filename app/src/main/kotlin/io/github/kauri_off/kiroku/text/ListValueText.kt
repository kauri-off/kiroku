package io.github.kauri_off.kiroku.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.showsCount

// Values from MAL and the user's list as interface text, one form for every screen.

// The list status line of a row ("Watching · 5 / 12"), shared by every screen that shows one.
@Composable
fun statusLine(
    values: ListValues,
    total: Int?,
): String {
    val name = stringResource(values.status.label())
    return when {
        !values.showsCount(total) -> name
        total == null -> stringResource(R.string.common_status_count, name, values.watchedEpisodes)
        else -> stringResource(R.string.common_status_progress, name, values.watchedEpisodes, total)
    }
}

@StringRes
fun ListStatus.label(): Int =
    when (this) {
        ListStatus.Watching -> R.string.common_status_watching
        ListStatus.Planned -> R.string.common_status_planned
        ListStatus.Completed -> R.string.common_status_completed
        ListStatus.OnHold -> R.string.common_status_on_hold
        ListStatus.Dropped -> R.string.common_status_dropped
    }

@Composable
fun mediaTypeLabel(type: MediaType): String = type.named { stringResource(it) }

// "TV · 2019"; either part is left out when unknown, so it can be empty.
@Composable
fun typeAndYear(
    type: MediaType?,
    year: Int?,
): String = listOfNotNull(type?.let { mediaTypeLabel(it) }, year?.toString()).joinToString(stringResource(R.string.common_separator))

// The relation's full name.
@Composable
fun relationName(relation: RelationType): String = relation.named { stringResource(it) }

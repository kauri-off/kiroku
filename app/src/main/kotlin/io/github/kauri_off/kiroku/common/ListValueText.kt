package io.github.kauri_off.kiroku.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.malCodeAsText
import io.github.kauri_off.kiroku.core.model.showsCount

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
fun mediaTypeLabel(type: MediaType): String =
    when (type) {
        MediaType.Tv -> stringResource(R.string.common_type_tv)
        MediaType.TvSpecial -> stringResource(R.string.common_type_tv_special)
        MediaType.Movie -> stringResource(R.string.common_type_movie)
        MediaType.Ova -> stringResource(R.string.common_type_ova)
        MediaType.Ona -> stringResource(R.string.common_type_ona)
        MediaType.Special -> stringResource(R.string.common_type_special)
        MediaType.Music -> stringResource(R.string.common_type_music)
        MediaType.Commercial -> stringResource(R.string.common_type_commercial)
        MediaType.PromotionalVideo -> stringResource(R.string.common_type_promotional)
        is MediaType.Unrecognized -> malCodeAsText(type.malCode)
    }

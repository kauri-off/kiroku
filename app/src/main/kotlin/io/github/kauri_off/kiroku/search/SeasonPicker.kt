package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.design.theme.Spacing

data class SeasonPickerState(
    val period: AnimeSeason,
    val latestYear: Int,
    val menu: SeasonMenu? = null,
)

enum class SeasonMenu { Season, Year }

private const val FIRST_BROWSE_YEAR = 1990

@Composable
internal fun SeasonPicker(
    state: SeasonPickerState,
    onSelect: (AnimeSeason) -> Unit,
    onMenu: (SeasonMenu?) -> Unit,
) {
    val period = state.period
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val season = seasonName(period.season)
        PeriodMenu(
            value = season,
            label = stringResource(R.string.search_season_picker, season),
            choices = Season.entries,
            expanded = state.menu == SeasonMenu.Season,
            onOpen = { onMenu(SeasonMenu.Season) },
            onClose = { onMenu(null) },
            name = { seasonName(it) },
            onSelect = { onSelect(period.copy(season = it)) },
        )
        PeriodMenu(
            value = period.year.toString(),
            label = stringResource(R.string.search_year_picker, period.year),
            choices = state.latestYear downTo minOf(FIRST_BROWSE_YEAR, period.year),
            expanded = state.menu == SeasonMenu.Year,
            onOpen = { onMenu(SeasonMenu.Year) },
            onClose = { onMenu(null) },
            name = { it.toString() },
            onSelect = { onSelect(period.copy(year = it)) },
        )
    }
}

@Composable
private fun <T> PeriodMenu(
    value: String,
    label: String,
    choices: Iterable<T>,
    expanded: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    name: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Box {
        OutlinedButton(
            onClick = onOpen,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Text(value)
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onClose) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(name(choice)) },
                    onClick = {
                        onClose()
                        onSelect(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun seasonName(season: Season): String =
    stringResource(
        when (season) {
            Season.Winter -> R.string.search_season_winter
            Season.Spring -> R.string.search_season_spring
            Season.Summer -> R.string.search_season_summer
            Season.Fall -> R.string.search_season_fall
        },
    )

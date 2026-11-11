package io.github.kauri_off.kiroku.titlesub

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.design.component.FactRow
import io.github.kauri_off.kiroku.design.component.ScreenState

// "Details" of section 9.9: one FactRow per fact MAL gives.
@Composable
fun DetailsScreen(
    state: DetailsState,
    onEvent: (DetailsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val body =
        when (state) {
            DetailsState.Loading -> SubScreenBody.State(ScreenState.Loading)
            is DetailsState.Failed -> SubScreenBody.State(errorState(state.error) { onEvent(DetailsEvent.Retry) })
            is DetailsState.Shown ->
                if (state.facts.isEmpty()) {
                    SubScreenBody.State(ScreenState.Empty(stringResource(R.string.titlesub_no_details)))
                } else {
                    SubScreenBody.List {
                        items(state.facts, key = { it.javaClass.name }) { fact -> FactRowOf(fact) }
                    }
                }
        }
    SubScreen(stringResource(R.string.titlesub_details), onBack = { onEvent(DetailsEvent.Back) }, body = body, modifier = modifier)
}

@Composable
private fun FactRowOf(fact: Fact) {
    when (fact) {
        is Fact.EnglishTitle -> FactRow(stringResource(R.string.titlesub_english_title), fact.title)
        is Fact.JapaneseTitle -> FactRow(stringResource(R.string.titlesub_japanese_title), fact.title)
        is Fact.Synonyms -> FactRow(stringResource(R.string.titlesub_synonyms), fact.titles)
        is Fact.StartDate -> FactRow(stringResource(R.string.titlesub_start_date), dateText(fact.date))
        is Fact.EndDate -> FactRow(stringResource(R.string.titlesub_end_date), dateText(fact.date))
        is Fact.SourceFact -> FactRow(stringResource(R.string.titlesub_source), sourceName(fact.source))
        is Fact.Studios ->
            FactRow(
                stringResource(if (fact.names.size == 1) R.string.titlesub_studio else R.string.titlesub_studios),
                fact.names.joinToString(stringResource(R.string.titlesub_list_separator)),
            )
        is Fact.Rating -> FactRow(stringResource(R.string.titlesub_rating), ratingName(fact.rating))
        is Fact.EpisodeLength -> FactRow(stringResource(R.string.titlesub_episode_length), lengthText(fact.length))
        is Fact.Broadcast -> FactRow(stringResource(R.string.titlesub_broadcast), broadcastText(fact.day, fact.time))
    }
}

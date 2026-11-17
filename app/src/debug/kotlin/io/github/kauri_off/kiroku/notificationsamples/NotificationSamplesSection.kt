package io.github.kauri_off.kiroku.notificationsamples

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.notify.Shown
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.debugmenu.SectionDetail
import io.github.kauri_off.kiroku.design.component.SectionHeader
import io.github.kauri_off.kiroku.design.theme.Spacing
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.wiring.appGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

// Whether the device showed the samples last posted; null before any.
data class NotificationSamplesState(
    val shown: Boolean? = null,
)

enum class NotificationSamplesEvent { OneTitle, SeveralTitles, SeveralToWatch, Remove }

// Posts samples through the real channel, so that what a notification says and how the group reads can be looked at.
@Composable
fun NotificationSamplesRoute() {
    val context = LocalContext.current
    val graph = context.appGraph
    val notifications = remember(graph) { EpisodeNotifications(context, { graph.titleLanguage }) }
    var state by remember { mutableStateOf(NotificationSamplesState()) }
    val scope = rememberCoroutineScope()
    NotificationSamplesSection(state) { event ->
        scope.launch {
            notifications.withdraw(notifications.shown().keys)
            // The device's time, not the source's: the demo's clock stands still.
            val now = Instant.now()
            samples(event, sampleTitles(graph.list), now).forEach { (anime, shown) -> notifications.post(anime, shown, alert = true) }
            state = NotificationSamplesState(notifications.enabled())
        }
    }
}

@Composable
fun NotificationSamplesSection(
    state: NotificationSamplesState,
    onEvent: (NotificationSamplesEvent) -> Unit,
) {
    SectionHeader(stringResource(R.string.notification_samples_title))
    SectionDetail(R.string.notification_samples_detail)
    if (state.shown == false) {
        Text(
            stringResource(R.string.notification_samples_blocked),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = Spacing.l, top = Spacing.s, end = Spacing.l),
        )
    }
    FlowRow(
        Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        for (event in NotificationSamplesEvent.entries) {
            OutlinedButton(onClick = { onEvent(event) }) { Text(stringResource(event.label)) }
        }
    }
}

private val NotificationSamplesEvent.label: Int
    get() =
        when (this) {
            NotificationSamplesEvent.OneTitle -> R.string.notification_samples_one
            NotificationSamplesEvent.SeveralTitles -> R.string.notification_samples_several
            NotificationSamplesEvent.SeveralToWatch -> R.string.notification_samples_to_watch
            NotificationSamplesEvent.Remove -> R.string.notification_samples_remove
        }

// A title and the episodes watched of it.
private data class SampleTitle(
    val anime: Anime,
    val watched: Int,
)

// The titles watched on the list, so that a tap opens a page the source has; the fixtures' when there are too few.
private suspend fun sampleTitles(list: ListSource): List<SampleTitle> {
    val listed =
        try {
            list.observeEntries().first().filter { it.status == ListStatus.Watching }.map {
                SampleTitle(
                    it.anime,
                    it.values.watchedEpisodes,
                )
            }
        } catch (_: DataException) {
            emptyList()
        }
    val fixtures = listOf(FixtureAnime.exactToWatch, FixtureAnime.longTitle, FixtureAnime.exactOnBreak).map { SampleTitle(it, WATCHED) }
    return (listed + fixtures).distinctBy { it.anime.id }.take(SEVERAL)
}

// Each title with what its notification says, as if its episode had aired at [now].
private fun samples(
    event: NotificationSamplesEvent,
    titles: List<SampleTitle>,
    now: Instant,
): List<Pair<Anime, Shown>> =
    when (event) {
        NotificationSamplesEvent.OneTitle -> titles.take(1).map { it.next(now) }
        NotificationSamplesEvent.SeveralTitles -> titles.map { it.next(now) }
        NotificationSamplesEvent.SeveralToWatch ->
            titles.take(1).map { it.anime to Shown(it.watched + TO_WATCH, TO_WATCH, it.watched + 1, now, now) }
        NotificationSamplesEvent.Remove -> emptyList()
    }

private fun SampleTitle.next(now: Instant) = anime to Shown(watched + 1, 1, watched + 1, now, now)

private const val SEVERAL = 3
private const val TO_WATCH = 3
private const val WATCHED = 4

package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

// How long what MAL answered is fresh: a screen shown within it asks MAL nothing (section 7.1).
object Freshness {
    val list: Duration = 15.minutes
    val details: Duration = 24.hours
    val airingDetails: Duration = 6.hours
}

// Never synced, owed a sync (W5) or synced too long ago.
fun listStale(
    syncedAt: Instant?,
    now: Instant,
): Boolean = stale(syncedAt, now, Freshness.list)

// A title's details, of which an airing title's change sooner.
fun detailsStale(
    anime: Anime?,
    refreshedAt: Instant?,
    now: Instant,
): Boolean = stale(refreshedAt, now, if (anime?.airingStatus == AiringStatus.Airing) Freshness.airingDetails else Freshness.details)

// A time after now means the clock was set back, so nothing is known of the age.
private fun stale(
    at: Instant?,
    now: Instant,
    fresh: Duration,
): Boolean = at == null || now < at || now >= at + fresh.toJavaDuration()

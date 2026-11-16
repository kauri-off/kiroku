package io.github.kauri_off.kiroku

import io.github.kauri_off.kiroku.core.airing.Airing
import io.github.kauri_off.kiroku.core.airing.AiringPhase
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.Premiere
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.UpNext
import io.github.kauri_off.kiroku.core.airing.titleAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.model.PartialDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// Only :core builds an airing value, so a test of a screen takes the calculator's and writes out what it expects as plain values.

private val unaired = FixtureAnime.premiereDate

private fun premiere(phase: AiringPhase?): Premiere = (phase as AiringPhase.NotYetAired).premiere

// The premiere of a title whose first episode is scheduled at [instant].
fun premiereAt(instant: Instant): Premiere {
    val schedule = AiringSchedule(listOf(ScheduledEpisode(1, instant)), null)
    return premiere(titleAiring(unaired, null, schedule, instant.minusSeconds(1), ZoneOffset.UTC).phase)
}

// The premiere of a title MAL dates [date], before that date.
fun premiereOn(date: PartialDate): Premiere {
    val airing: Airing? = null
    return premiere(titleAiring(unaired.copy(startDate = date), null, airing, LocalDate.EPOCH).phase)
}

// Aired: the count, "to watch" and the next episode. Not yet aired: the premiere.
fun AiringPhase?.plain(): Any? =
    when (this) {
        is AiringPhase.Aired -> Triple(aired, toWatch, next)
        is AiringPhase.NotYetAired -> premiere.plain()
        AiringPhase.CountUnknown, null -> this
    }

fun Premiere.plain(): Any =
    when (this) {
        is Premiere.At -> instant
        is Premiere.OnDay -> date
        is Premiere.InMonth -> yearMonth
        is Premiere.InYear -> year
        Premiere.Unannounced -> this
    }

// To watch: the time of the latest aired episode, in a list. Coming: its time.
fun UpNext.plain(): Any =
    when (this) {
        is UpNext.ToWatch -> listOf(latestAiredAt)
        is UpNext.Coming -> at
        UpNext.Rest -> this
    }

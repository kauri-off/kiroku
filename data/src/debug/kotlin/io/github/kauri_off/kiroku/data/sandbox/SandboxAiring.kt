package io.github.kauri_off.kiroku.data.sandbox

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// One episode of a schedule and the moment it airs.
data class Aired(
    val episode: Int,
    val at: Instant,
)

// One entry AniList keeps for a MAL title, as of now: its own total, its state and its schedule, oldest first.
data class AniListEntry(
    val aniListId: Long,
    val idMal: Long,
    val episodes: Int?,
    val status: String,
    val schedule: List<Aired>,
)

// How the sandbox's titles air, laid out around the moment it started, which [file] keeps and a reset moves to now.
class SandboxAiring(
    val titles: Map<Long, JsonObject>,
    private val clock: Clock,
    private val file: File? = null,
) {
    @Volatile
    var since: Instant = read() ?: started()
        private set

    // Every schedule laid out again around now, as when the sandbox first started.
    fun reset() {
        since = started()
    }

    // AniList's entries for a MAL title as of now; none for a title AniList does not know.
    fun entries(id: Long): List<AniListEntry> {
        val title = titles[id] ?: return emptyList()
        if (id == UNKNOWN) return emptyList()
        val now = clock.instant()
        val plan = plan(id, title) ?: return listOf(AniListEntry(id, id, title.episodes, aniListStatus(title.text("status")), emptyList()))
        return plan.entries.map { AniListEntry(it.aniListId, id, it.episodes, it.status(now), it.schedule) }
    }

    // MAL's page of a title as of now: where the sandbox lays out its airing, its dates and state are those of the same episodes.
    fun page(id: Long): JsonObject? {
        val title = titles[id] ?: return null
        val plan = plan(id, title) ?: return title
        val first = plan.entries.first()
        val now = clock.instant()
        val page = title.toMutableMap()
        plan.start?.let { start ->
            page["start_date"] = JsonPrimitive(start)
            val month = start.take(MONTH.length).takeIf { it.length == MONTH.length }
            if ("start_season" in title && month != null) page["start_season"] = season(YearMonth.parse(month))
        }
        plan.slot?.let { page["broadcast"] = it.json() }
        if (plan.malFollows) {
            val status = malStatus(first.status(now))
            page["status"] = JsonPrimitive(status)
            val last = first.schedule.lastOrNull()?.takeIf { status == FINISHED_AIRING }
            if (last == null) page.remove("end_date") else page["end_date"] = JsonPrimitive(japanDate(last.at).toString())
        }
        return JsonObject(page)
    }

    // Every title's MAL page as of now.
    fun pages(): Map<Long, JsonObject> = titles.keys.associateWith { page(it)!! }

    private class Laid(
        val aniListId: Long,
        val episodes: Int?,
        val schedule: List<Aired>,
        // The schedule holds the title's first episode, and the last one when it [ends].
        val starts: Boolean,
        val ends: Boolean,
        val fixed: String? = null,
    ) {
        fun status(now: Instant): String =
            when {
                fixed != null -> fixed
                starts && schedule.isNotEmpty() && now < schedule.first().at -> NOT_YET_RELEASED
                ends && schedule.isNotEmpty() && now >= schedule.last().at -> FINISHED
                else -> RELEASING
            }
    }

    private class Plan(
        val entries: List<Laid>,
        // MAL's start date, in its own precision; null keeps MAL's.
        val start: String? = null,
        // MAL's broadcast slot, where the sandbox gives the title one of its own.
        val slot: Slot? = null,
        // MAL's state and end follow the first entry's; false where the two sources disagreeing is the case.
        val malFollows: Boolean = true,
    )

    private fun plan(
        id: Long,
        title: JsonObject,
    ): Plan? {
        val slot = title.slot ?: OwnSlots[id]
        val next = slot?.after(since)
        val total = title.episodes
        val now = clock.instant()
        return when (id) {
            WEEKLY -> {
                // Once the episode before it has aired, AniList puts the next one and every later one a week later.
                val laid = weekly(MOVED - 1, next!!, 1..total!!)
                val moved = if (now > laid.first { it.episode == MOVED - 1 }.at) laid.map { it.later(MOVED) } else laid
                laid(Laid(id, total, moved, starts = true, ends = true))
            }
            SOON -> {
                val soon = Slot.of(since.plus(SOON_AFTER).truncatedTo(ChronoUnit.MINUTES))
                laid(Laid(id, total, weekly(4, soon.after(since), 1..total!!), starts = true, ends = true), slot = soon)
            }
            ON_A_BREAK -> {
                val aired = weekly(BREAK_LAST, next!!.minus(WEEK.multipliedBy(3)), BREAK_FIRST..BREAK_LAST)
                val resumed = weekly(BREAK_LAST + 1, next.plus(WEEK.multipliedBy(4)), BREAK_LAST + 1..BREAK_LAST + 10)
                Plan(listOf(Laid(id, null, aired + resumed, starts = false, ends = false)))
            }
            LONG_RUNNING -> {
                // Numbered by the weeks since MAL's start, so that no number is one the show cannot have reached.
                val started = slot!!.after(japanDate(title.text("start_date")!!).atStartOfDay(JAPAN).toInstant().minusSeconds(1))
                val number = (Duration.between(started, next!!).toDays() / 7).toInt() + 1
                Plan(listOf(Laid(id, null, weekly(number, next, number - LONG_AIRED..number + LONG_COMING), starts = false, ends = false)))
            }
            WITHOUT_TIMES -> Plan(listOf(Laid(id, total, emptyList(), starts = false, ends = false, fixed = RELEASING)))
            TWO_ENTRIES -> {
                val later = weekly(62, next!!.minus(WEEK.multipliedBy(3)), 24..62)
                val earlier = weekly(52, later.first().at.minus(WEEK.multipliedBy(10)), 1..52)
                Plan(
                    listOf(
                        Laid(id, 52, earlier, starts = true, ends = true),
                        Laid(SECOND_ENTRY, 62, later, starts = false, ends = true),
                    ),
                    start = japanDate(earlier.first().at).toString(),
                    malFollows = false,
                )
            }
            NUMBERED_ON -> {
                // AniList drops the season's last episode a while after the start, its total with it.
                val laid = weekly(175, next!!, 171..182)
                val removed = !now.isBefore(since.plus(REMOVED_AFTER))
                laid(Laid(id, if (removed) 11 else 12, if (removed) laid.dropLast(1) else laid, starts = true, ends = true))
            }
            COMING -> laid(Laid(id, total, weekly(1, next!!.plus(WEEK.multipliedBy(2)), 1..total!!), starts = true, ends = true))
            COMING_WITHOUT_TIME ->
                Plan(
                    listOf(Laid(id, 12, emptyList(), starts = false, ends = false, fixed = NOT_YET_RELEASED)),
                    start = YearMonth.from(japanDate(since.plus(WEEK.multipliedBy(13)))).toString(),
                )
            FILM -> {
                val shown = weekly(1, next!!.plus(WEEK.multipliedBy(6)), 1..2)
                Plan(listOf(Laid(id, 2, shown, starts = true, ends = true)), start = japanDate(shown.first().at).year.toString())
            }
            FINISHED_RECENTLY ->
                laid(
                    Laid(id, total, weekly(total!!, next!!.minus(WEEK.multipliedBy(2)), 1..total), starts = true, ends = true),
                )
            else -> null
        }
    }

    // One entry whose first episode is MAL's start date.
    private fun laid(
        entry: Laid,
        slot: Slot? = null,
    ) = Plan(listOf(entry), start = japanDate(entry.schedule.first().at).toString(), slot = slot)

    private fun started(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS).also(::kept)

    private fun kept(since: Instant) {
        val file = file ?: return
        val next = File(file.path + ".next")
        next.writeText(since.epochSecond.toString())
        check(next.renameTo(file)) { "The sandbox's airing was not kept" }
    }

    private fun read(): Instant? =
        try {
            file
                ?.takeIf { it.isFile }
                ?.readText()
                ?.trim()
                ?.toLongOrNull()
                ?.let(Instant::ofEpochSecond)
        } catch (_: IOException) {
            null
        }

    companion object {
        // The sandbox's titles that air, one case each.
        const val WEEKLY = 61987L
        const val SOON = 64534L
        const val ON_A_BREAK = 21L
        const val LONG_RUNNING = 50250L
        const val WITHOUT_TIMES = 55809L
        const val TWO_ENTRIES = 51039L
        const val NUMBERED_ON = 61967L
        const val COMING = 57557L
        const val COMING_WITHOUT_TIME = 56732L
        const val FILM = 57584L
        const val FINISHED_RECENTLY = 60568L
        const val UNKNOWN = 60988L

        // AniList's own id of the second entry it keeps for TWO_ENTRIES; every other entry's is its MAL id.
        private const val SECOND_ENTRY = 1_051_039L

        // A reset is followed this soon by an episode of SOON.
        val SOON_AFTER: Duration = Duration.ofMinutes(5)

        // WEEKLY's episode that AniList moves a week later once the one before it has aired, with every later one.
        const val MOVED = 7

        // NUMBERED_ON's last episode is gone from AniList this long after the start.
        val REMOVED_AFTER: Duration = Duration.ofDays(10)

        val JAPAN: ZoneId = ZoneId.of("Asia/Tokyo")

        const val RELEASING = "RELEASING"
        const val FINISHED = "FINISHED"
        const val NOT_YET_RELEASED = "NOT_YET_RELEASED"
        private const val FINISHED_AIRING = "finished_airing"

        private val WEEK = Duration.ofDays(7)
        private const val MONTH = "yyyy-MM"

        // Two pages of AniList's schedule exactly.
        private const val BREAK_FIRST = 1141
        private const val BREAK_LAST = 1180
        private const val LONG_AIRED = 150
        private const val LONG_COMING = 9

        // A slot of the sandbox's own for titles MAL gives none.
        private val OwnSlots =
            mapOf(
                TWO_ENTRIES to Slot(DayOfWeek.SUNDAY, LocalTime.of(11, 0)),
                COMING to Slot(DayOfWeek.WEDNESDAY, LocalTime.of(12, 0)),
                FILM to Slot(DayOfWeek.FRIDAY, LocalTime.of(0, 0)),
                FINISHED_RECENTLY to Slot(DayOfWeek.SATURDAY, LocalTime.of(18, 0)),
            )

        private fun weekly(
            anchor: Int,
            anchorAt: Instant,
            episodes: IntRange,
        ): List<Aired> = episodes.map { Aired(it, anchorAt.plus(WEEK.multipliedBy((it - anchor).toLong()))) }

        private fun Aired.later(from: Int) = if (episode >= from) copy(at = at.plus(WEEK)) else this

        fun japanDate(at: Instant): LocalDate = at.atZone(JAPAN).toLocalDate()

        private fun japanDate(text: String): LocalDate =
            try {
                LocalDate.parse(text)
            } catch (_: DateTimeParseException) {
                YearMonth.parse(text.take(MONTH.length)).atDay(1)
            }

        private fun aniListStatus(mal: String?): String =
            when (mal) {
                FINISHED_AIRING -> FINISHED
                "not_yet_aired" -> NOT_YET_RELEASED
                else -> RELEASING
            }

        private fun malStatus(aniList: String): String =
            when (aniList) {
                FINISHED -> FINISHED_AIRING
                NOT_YET_RELEASED -> "not_yet_aired"
                else -> "currently_airing"
            }

        private fun season(month: YearMonth): JsonElement =
            buildJsonObject {
                put("year", month.year)
                put("season", listOf("winter", "spring", "summer", "fall")[(month.monthValue - 1) / 3])
            }
    }
}

// A weekly slot in Japan's time, as MAL's broadcast gives one.
data class Slot(
    val day: DayOfWeek,
    val time: LocalTime,
) {
    // The first moment of this slot after [instant].
    fun after(instant: Instant): Instant {
        val local = instant.atZone(SandboxAiring.JAPAN)
        val at = local.with(TemporalAdjusters.nextOrSame(day)).with(time)
        return (if (at.toInstant().isAfter(instant)) at else at.plusWeeks(1)).toInstant()
    }

    fun json(): JsonObject =
        buildJsonObject {
            put("day_of_the_week", day.name.lowercase())
            put("start_time", time.truncatedTo(ChronoUnit.MINUTES).toString())
        }

    companion object {
        fun of(at: Instant): Slot = at.atZone(SandboxAiring.JAPAN).let { Slot(it.dayOfWeek, it.toLocalTime()) }
    }
}

// MAL's broadcast slot of a title, when it gives one.
internal val JsonObject.slot: Slot?
    get() {
        val broadcast = get("broadcast") as? JsonObject ?: return null
        val day = broadcast.text("day_of_the_week")?.uppercase()?.let { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
        val time = broadcast.text("start_time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        return if (day != null && time != null) Slot(day, time) else null
    }

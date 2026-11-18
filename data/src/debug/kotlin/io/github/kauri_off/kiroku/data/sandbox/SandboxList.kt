package io.github.kauri_off.kiroku.data.sandbox

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

// The sandbox's MAL: its titles and their copies, its user's list kept in [file], which outlives the process as the
// website's does, and the lists of its other users.
class SandboxList(
    private val titles: Map<Long, JsonObject>,
    private val clock: Clock,
    private val file: File? = null,
    private val starting: (Instant) -> Map<Long, Listed> = { SandboxTitles.startingList(titles, it) },
    // The list of SandboxUsers.PUBLIC, made of [titles] and their copies.
    public: (Map<Long, JsonObject>) -> Map<Long, Listed> = SandboxUsers::publicList,
) : MalList {
    // The starting list is kept at once, so that the next process finds it as this one left it.
    private val list: MutableMap<Long, Listed> = read()?.toMutableMap() ?: starting(clock.instant()).toMutableMap().also { kept(it) }

    private val publicList by lazy { inTitleOrder(public(titles).toList()) }

    override fun title(id: Long): JsonObject? = SandboxUsers.titleOf(id, titles::get)

    override fun entries(): List<Pair<Long, Listed>> = inTitleOrder(synchronized(list) { list.toList() })

    private fun inTitleOrder(entries: List<Pair<Long, Listed>>): List<Pair<Long, Listed>> =
        entries
            .map { title(it.first)?.text("title")?.lowercase() to it }
            .sortedWith(compareBy({ it.first }, { it.second.first }))
            .map { it.second }

    override fun entry(id: Long): Listed? = synchronized(list) { list[id] }

    override fun usersList(name: String): UsersList? =
        when {
            name.equals(SandboxMal.USER_NAME, ignoreCase = true) -> UsersList.Public(entries())
            name.equals(SandboxUsers.PUBLIC, ignoreCase = true) -> UsersList.Public(publicList)
            name.equals(SandboxUsers.PRIVATE, ignoreCase = true) -> UsersList.Private
            else -> null
        }

    override fun write(
        id: Long,
        form: Map<String, String>,
    ): Written {
        val title = title(id) ?: return Written.Unknown
        return synchronized(list) {
            val entry = written(list[id], form, title.episodes, clock.instant()) ?: return Written.Refused
            list[id] = entry
            kept()
            Written.Done(entry)
        }
    }

    override fun remove(id: Long): Boolean {
        if (title(id) == null) return false
        synchronized(list) {
            list.remove(id)
            kept()
        }
        return true
    }

    // The list the sandbox started with, as of now.
    fun reset() {
        synchronized(list) {
            list.clear()
            list += starting(clock.instant())
            kept()
        }
    }

    private fun kept(entries: Map<Long, Listed> = list) {
        val file = file ?: return
        val next = File(file.path + ".next")
        next.writeText(json.encodeToString(Stored.serializer(), Stored(entries.mapValues { (_, it) -> it.stored() })))
        check(next.renameTo(file)) { "The sandbox's list was not kept" }
    }

    private fun read(): Map<Long, Listed>? =
        try {
            file?.takeIf { it.isFile }?.let {
                json.decodeFromString(Stored.serializer(), it.readText()).entries.mapValues { (_, it) ->
                    it.listed()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    @Serializable
    private class Stored(
        val entries: Map<Long, StoredEntry>,
    )

    @Serializable
    private class StoredEntry(
        val status: String,
        val score: Int,
        val watched: Int,
        val start: String? = null,
        val finish: String? = null,
        val updated: Long,
    ) {
        fun listed() = Listed(status, score, watched, start, finish, Instant.ofEpochSecond(updated))
    }

    private fun Listed.stored() = StoredEntry(status, score, watched, startDate, finishDate, updatedAt.epochSecond)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

// MAL's five list statuses, by code.
val ListStatusCodes = setOf("watching", "completed", "on_hold", "dropped", "plan_to_watch")

// A write as MAL took it in the owner's recordings (SandboxRecordingsTest), or null where MAL refuses it with 400.
internal fun written(
    entry: Listed?,
    form: Map<String, String>,
    total: Int?,
    now: Instant,
): Listed? {
    val status = form["status"]?.also { if (it !in ListStatusCodes) return null } ?: entry?.status ?: return null
    val score = form["score"]?.let { it.toIntOrNull()?.takeIf { score -> score in 0..10 } ?: return null } ?: entry?.score ?: 0
    val watched =
        form["num_watched_episodes"]?.let { it.toIntOrNull()?.takeIf { count -> count >= 0 } ?: return null } ?: entry?.watched ?: 0
    val start = if ("start_date" in form) date(form.getValue("start_date")) ?: return null else Kept(entry?.startDate)
    val finish = if ("finish_date" in form) date(form.getValue("finish_date")) ?: return null else Kept(entry?.finishDate)
    return Listed(
        status = status,
        score = score,
        watched = if (total != null) minOf(watched, total) else watched,
        startDate = start.value,
        finishDate = finish.value,
        updatedAt = now.truncatedTo(ChronoUnit.SECONDS),
    )
}

private class Kept(
    val value: String?,
)

// What a sent date leaves: none for an empty or zero date, the date as sent, or null for one that cannot be read.
private fun date(sent: String): Kept? {
    if (sent.isEmpty() || sent == ZERO_DATE) return Kept(null)
    return try {
        when (sent.count { it == '-' }) {
            0 -> Year.parse(sent)
            1 -> YearMonth.parse(sent)
            2 -> LocalDate.parse(sent)
            else -> return null
        }
        Kept(sent)
    } catch (_: DateTimeParseException) {
        null
    }
}

private const val ZERO_DATE = "0000-00-00"

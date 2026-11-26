package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.data.manga.form
import io.github.kauri_off.kiroku.data.network.MalFields

enum class ReadCapture(
    internal val key: String,
    internal val request: String,
    internal val pages: Int,
) {
    Suggestions("suggestions", "anime/suggestions?fields=${MalFields.SUGGESTIONS}&limit=10", 2),
    MangaList(
        "manga_list",
        "users/@me/mangalist?fields=id,title,main_picture,alternative_titles,media_type,status,num_chapters,num_volumes," +
            "list_status{status,score,num_chapters_read,num_volumes_read,updated_at}&nsfw=true&sort=manga_title&limit=2",
        500,
    ),
    ;

    internal fun answer(index: Int): String =
        when (this) {
            Suggestions -> if (index == 0) "suggestions_first" else "suggestions_next"
            MangaList -> "manga_list_${index + 1}"
        }
}

internal fun isSignedInAnswer(name: String): Boolean =
    name in SignedInAnswers ||
        name in MangaMutationAnswers ||
        name == MANGA_ABSENT ||
        name == MANGA_REMOVED ||
        name == MANGA_REMOVED_READ ||
        MangaWrites.any { name == it.first || name == "${it.first}_read" } ||
        name == "suggestions_first" ||
        name == "suggestions_next" ||
        (name.startsWith("manga_list_") && name.removePrefix("manga_list_").toIntOrNull()?.let { it > 0 } == true)

private val MangaMutationAnswers = setOf("manga_mutation_patch", "manga_mutation_delete", "manga_mutation_read_back")

// A change of the recorder's own title on the list: [form] is sent as it is, and the entry is read back after it.
internal class OwnWrite(
    val name: String,
    val form: (episodes: Int) -> Map<String, String>,
) {
    constructor(name: String, vararg form: Pair<String, String>) : this(name, { form.toMap() })
}

// The changes in order: the first adds the title and has to succeed; MAL's refusal of any other is an answer.
internal val OwnTitleWrites: List<OwnWrite> =
    listOf(
        // What a write answers with, and the values MAL gives an entry that was only given a status.
        OwnWrite("add_planned", "status" to "plan_to_watch"),
        // W7: what MAL itself does with the status and the dates as the count goes up, reaches the total and passes it.
        OwnWrite("count_first", "num_watched_episodes" to "1"),
        OwnWrite("count_total") { mapOf("num_watched_episodes" to "$it") },
        // W9: whether MAL takes a count above the total.
        OwnWrite("count_above_total") { mapOf("num_watched_episodes" to "${it + 1}") },
        OwnWrite("count_below_total") { mapOf("num_watched_episodes" to "${it - 1}") },
        // W7: whether the status alone brings the count and a finish date with it.
        OwnWrite("completed", "status" to "completed"),
        // W3: whether a date can be set, and cleared by an empty value.
        OwnWrite("finish_set", "finish_date" to "2020-02-02"),
        OwnWrite("finish_cleared", "finish_date" to ""),
        OwnWrite("start_set", "start_date" to "2020-01-01"),
        OwnWrite("start_cleared", "start_date" to ""),
        // Two values in one write, the finish before the start.
        OwnWrite("dates_reversed", "start_date" to "2020-03-03", "finish_date" to "2020-02-02"),
        // The other value that might clear a date.
        OwnWrite("dates_zeroed", "start_date" to "0000-00-00", "finish_date" to "0000-00-00"),
        OwnWrite("date_partial", "start_date" to "2020-05"),
        OwnWrite("date_future", "finish_date" to "2100-01-01"),
        OwnWrite("score_set", "score" to "7"),
        OwnWrite("score_cleared", "score" to "0"),
        // Values MAL should refuse.
        OwnWrite("score_refused", "score" to "11"),
        OwnWrite("status_refused", "status" to "kiroku"),
    )

// The lifetime MAL gave the access token: the one part of a token answer that is stored.
internal const val TOKEN_GRANTED = "token_granted"

// The requests of a recording on a list of one page: profile, list, three pages, the title, each write and its read-back,
// the whole entry, two removals with the read-back between them, and the list again.
val RecordingRequests: Int = 11 + 2 * OwnTitleWrites.size

// Every answer the signed-in recorder stores, in the order of its requests; none of them is in the repository (section 11.1).
internal val SignedInAnswers: List<String> =
    listOf(TOKEN_GRANTED, "profile", "own_list_1", "own_list_2", "own_list_3", "entry_absent") +
        OwnTitleWrites.flatMap { listOf(it.name, "${it.name}_read") } +
        listOf("entry_full", "removed", "removed_read", "removed_again")

internal val MangaWrites =
    listOf(
        "manga_add" to MangaListEdit(status = MangaListStatus.PlanToRead).form(),
        "manga_edit" to MangaListEdit(status = MangaListStatus.Reading, chaptersRead = 1, volumesRead = 1, score = 7).form(),
    )

internal const val MANGA_ABSENT = "manga_entry_absent"
internal const val MANGA_REMOVED = "manga_removed"
internal const val MANGA_REMOVED_READ = "manga_removed_read"

val MangaRecordingAnswers: Int = 3 + 2 * MangaWrites.size

internal fun mangaDeleted(status: Int) = status == 200 || status == 204

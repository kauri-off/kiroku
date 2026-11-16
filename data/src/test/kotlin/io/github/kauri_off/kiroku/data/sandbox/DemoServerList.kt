package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.core.demo.DemoServer
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.sent
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListValues
import io.github.kauri_off.kiroku.data.db.airingStatuses
import io.github.kauri_off.kiroku.data.db.listStatuses
import io.github.kauri_off.kiroku.data.db.mediaTypes
import io.github.kauri_off.kiroku.data.db.partialDateOf
import io.github.kauri_off.kiroku.data.db.toColumn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

// The contract's scripted server as the list the sandbox answers from: its titles in MAL's form, the sandbox's rules.
internal class DemoServerList(
    private val server: DemoServer,
) : MalList {
    override fun title(id: Long): JsonObject? = server.title(AnimeId(id))?.toNode()

    override val pageSize: Int get() = server.pageSize

    // Each page of a download reads the list anew.
    override fun entries(): List<Pair<Long, Listed>> = server.pageRead().map { it.anime.id.value to it.listed() }

    override fun entry(id: Long): Listed? = server.entry(AnimeId(id))?.listed()

    // The sandbox's own rule for a write, its result stored in the server's list as the server stores any write.
    override fun write(
        id: Long,
        form: Map<String, String>,
    ): Written {
        val title = server.title(AnimeId(id)) ?: return Written.Unknown
        val listed = written(entry(id), form, title.malEpisodes, Instant.EPOCH) ?: return Written.Refused
        val sent =
            ListValues(
                listStatuses.of(listed.status),
                listed.watched,
                listed.score.takeIf { it > 0 },
                listed.startDate?.let(::partialDateOf),
                listed.finishDate?.let(::partialDateOf),
            ).sent
        return when (val outcome = server.applied(AnimeId(id), sent)) {
            is Outcome.Success -> Written.Done(checkNotNull(outcome.value).listed())
            is Outcome.Failure -> if (outcome.error == DataError.NotFound) Written.Unknown else Written.Refused
        }
    }

    override fun remove(id: Long): Boolean = server.applied(AnimeId(id), null) is Outcome.Success
}

private fun ListEntry.listed() =
    Listed(
        status = listStatuses.toColumn(values.status),
        score = values.score ?: 0,
        watched = values.watchedEpisodes,
        startDate = values.startDate?.toColumn(),
        finishDate = values.finishDate?.toColumn(),
        updatedAt = updatedAt,
    )

// A title as MAL's answers write it, every value the app reads in MAL's own form.
internal fun Anime.toNode(): JsonObject =
    buildJsonObject {
        put("id", id.value)
        put("title", titles.romaji)
        posterUrl?.let { medium ->
            putJsonObject("main_picture") {
                put("medium", medium)
                largePosterUrl?.let { put("large", it) }
            }
        }
        putJsonObject("alternative_titles") {
            putJsonArray("synonyms") { titles.synonyms.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("en", titles.english.orEmpty())
            put("ja", titles.japanese.orEmpty())
        }
        mediaType?.let { put("media_type", mediaTypes.toColumn(it)) }
        put("status", airingStatuses.toColumn(airingStatus))
        put("num_episodes", malEpisodes ?: 0)
        startDate?.let { put("start_date", it.toColumn()) }
        broadcast?.let { broadcast ->
            putJsonObject("broadcast") {
                put("day_of_the_week", broadcast.dayOfWeek.name.lowercase())
                broadcast.japanTime?.let { put("start_time", it.toString()) }
            }
        }
        meanScore?.let { put("mean", it) }
        rank?.let { put("rank", it) }
        genres?.let { genres ->
            putJsonArray("genres") {
                genres.forEach { genre ->
                    add(
                        buildJsonObject {
                            put("id", genre.id)
                            put("name", genre.malName)
                        },
                    )
                }
            }
        }
        averageEpisodeDuration?.let { put("average_episode_duration", it.seconds) }
    }

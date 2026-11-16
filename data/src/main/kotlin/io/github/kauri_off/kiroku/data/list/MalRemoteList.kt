package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.RemoteList
import io.github.kauri_off.kiroku.core.list.SentValues
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.ListField
import io.github.kauri_off.kiroku.data.anime.AnimeNode
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.anime.toAnime
import io.github.kauri_off.kiroku.data.db.listStatuses
import io.github.kauri_off.kiroku.data.db.toColumn
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.AnotherTitleAnswer
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.authorised
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import retrofit2.http.DELETE
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query

// The signed-in user's list on MAL, as the engine uses it.
class MalRemoteList(
    private val http: MalHttp,
    private val session: MalSession,
) : RemoteList {
    private val lists = MalLists(http, session)
    private val api by http.api(EntryApi::class)

    override suspend fun fetch(): Outcome<List<ListEntry>> = lists.list(ListUser.SignedIn)

    // A title MAL does not know is not found; one it knows gives no list values when it is not on the list.
    override suspend fun fetchEntry(id: AnimeId): Outcome<ListEntry?> {
        val answer = read { repairs, token -> api.entry(id.value, MalFields.OWN_ENTRY, token).toOwnEntry(repairs) }
        if (answer is Outcome.Success && answer.value.first != id) {
            http.unexpected(AnotherTitleAnswer())
            return Outcome.Failure(DataError.ServerError)
        }
        return answer.map { it.second }
    }

    override suspend fun put(
        anime: Anime,
        values: SentValues?,
    ): Outcome<ListEntry?> = if (values == null) remove(anime.id) else set(anime, values)

    // MAL answers a change with the entry's values afterwards, the same as a read of it gives.
    private suspend fun set(
        anime: Anime,
        values: SentValues,
    ): Outcome<ListEntry?> {
        val form = values.form()
        return read { repairs, token -> listEntryOf(anime, api.set(anime.id.value, form, token), repairs) }
    }

    // MAL answers a removal with an empty array, also for a title not on the list; a 404 for that is a removal too.
    // Any other answer, a page a redirect led to included, is not a removal.
    private suspend fun remove(id: AnimeId): Outcome<ListEntry?> =
        when (val answer = session.authorised(http) { check(api.remove(id.value, it) == REMOVED) { "Not a removal" } }) {
            is Outcome.Success -> Outcome.Success(null)
            is Outcome.Failure -> if (answer.error == DataError.NotFound) Outcome.Success(null) else answer
        }

    // An answer read with values left out or filled in is reported once it is read.
    private suspend fun <T> read(request: suspend (Repairs, authorization: String) -> T): Outcome<T> {
        val repairs = Repairs()
        val answer = session.authorised(http) { request(repairs, it) }
        if (answer is Outcome.Success && repairs.fields.isNotEmpty()) http.unexpected(RepairedAnswer(repairs.fields))
        return answer
    }
}

// Each value a write carries, in MAL's own form: no score is 0, and an empty date clears MAL's.
internal fun SentValues.form(): Map<String, String> =
    fields.associate { field ->
        when (field) {
            ListField.Status -> "status" to listStatuses.toColumn(checkNotNull(status))
            ListField.Score -> "score" to "${values.score ?: 0}"
            ListField.WatchedEpisodes -> "num_watched_episodes" to "${values.watchedEpisodes}"
            ListField.StartDate -> "start_date" to values.startDate?.toColumn().orEmpty()
            ListField.FinishDate -> "finish_date" to values.finishDate?.toColumn().orEmpty()
        }
    }

// A title's own answer: its id, and its entry when it is on the list, which MAL says by giving its list values.
private fun JsonObject.toOwnEntry(repairs: Repairs): Pair<AnimeId, ListEntry?> {
    val anime = MalJson.decodeFromJsonElement(AnimeNode.serializer(), this).toAnime(repairs.within("node"))
    val values = get(MY_LIST_STATUS)?.takeUnless { it is JsonNull } ?: return anime.id to null
    return anime.id to listEntryOf(anime, values, repairs)
}

private const val MY_LIST_STATUS = "my_list_status"

private val REMOVED = JsonArray(emptyList())

private fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> =
    when (this) {
        is Outcome.Success -> Outcome.Success(transform(value))
        is Outcome.Failure -> this
    }

private interface EntryApi {
    @GET("anime/{id}")
    suspend fun entry(
        @Path("id") id: Long,
        @Query("fields") fields: String,
        @Header(AUTHORIZATION) authorization: String,
    ): JsonObject

    @FormUrlEncoded
    @PATCH("anime/{id}/my_list_status")
    suspend fun set(
        @Path("id") id: Long,
        @FieldMap form: Map<String, String>,
        @Header(AUTHORIZATION) authorization: String,
    ): JsonObject

    @DELETE("anime/{id}/my_list_status")
    suspend fun remove(
        @Path("id") id: Long,
        @Header(AUTHORIZATION) authorization: String,
    ): JsonElement
}

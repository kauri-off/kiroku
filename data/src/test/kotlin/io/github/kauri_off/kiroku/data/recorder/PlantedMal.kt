package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

// Not MAL and no mapper's fixture: it keeps a list as requests change it and plants a user's values in its answers.
internal class PlantedMal : Dispatcher() {
    class Title(
        val name: String,
        val episodes: Int,
        val status: String = "finished_airing",
    )

    // Every request in the order it arrived: the method, the path with its query, and the body of a write.
    val asked = CopyOnWriteArrayList<String>()
    val tokens = CopyOnWriteArrayList<String>()

    @Volatile
    var valid = setOf(ACCESS)

    @Volatile
    var user = USER_ID

    // The titles MAL knows besides the ones on the list.
    @Volatile
    var titles = mapOf(TITLE to Title("A finished title", 12))

    // False: a title's own answer does not say that the title is on the list.
    @Volatile
    var tellsTheEntry = true

    // False: a removal is answered without a body.
    @Volatile
    var answersRemovalsInJson = true

    // The most entries a page holds, whatever limit is asked; a list that goes on names its next page.
    @Volatile
    var largestPage = 1000

    // True: the link to the next page says no place to go on from.
    @Volatile
    var brokenNext = false

    // True: a removal is answered as done and removes nothing.
    @Volatile
    var removalsDoNothing = false

    // Titles the whole list keeps quiet about, and titles it goes on showing after their removal.
    @Volatile
    var listHides = emptySet<Long>()

    @Volatile
    var listStillShows = emptySet<Long>()

    // An answer in place of the server's own, for the requests a test wants to fail.
    @Volatile
    var instead: (RecordedRequest) -> MockResponse? = { null }

    // Called when a request has arrived, before it is answered.
    @Volatile
    var arrived: (String) -> Unit = {}

    private val list = sortedMapOf<Long, Map<String, String>>()

    init {
        for (id in LISTED) list[id] = if (id > LAST_COMPLETED) PERSONAL_ENTRY + ("status" to "dropped") else PERSONAL_ENTRY
    }

    // A title the user put on the list elsewhere while a recording ran.
    fun addedElsewhere(id: Long) = synchronized(list) { list[id] = PERSONAL_ENTRY }

    // The list as the website would show it.
    fun onTheList(): Map<Long, Map<String, String>> = synchronized(list) { list.toMap() }

    val writes: List<String> get() = asked.filter { it.startsWith("PATCH") || it.startsWith("DELETE") }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val body = request.body?.utf8().orEmpty()
        val line = "${request.method} ${request.target}${if (body.isEmpty()) "" else " $body"}"
        asked += line
        arrived(line)
        val token = request.headers["Authorization"].orEmpty().removePrefix("Bearer ")
        tokens += token
        instead(request)?.let { return it }
        if (token !in valid) return answer(401, """{"error":"invalid_token"}""")
        val path = request.url.encodedPath.removePrefix("/v2/")
        val query = request.url
        return synchronized(list) {
            when {
                path == "users/@me" -> answer(200, if (query.queryParameter("fields") == "id") """{"id":$user}""" else profile())
                path == "users/@me/animelist" ->
                    page(
                        query.queryParameter("fields") != null,
                        query.queryParameter("status"),
                        query.queryParameter("limit")!!.toInt(),
                        query.queryParameter("offset")?.toInt() ?: 0,
                        token,
                    )
                path.endsWith("/my_list_status") -> change(path.split('/')[1].toLong(), request.method, form(body), token)
                path.startsWith("anime/") -> title(path.removePrefix("anime/").toLong())
                else -> answer(404, NOT_FOUND)
            }
        }
    }

    private fun profile(): String =
        buildJsonObject {
            put("id", user)
            put("name", USER_NAME)
            put("picture", PICTURE)
            put("gender", "planted-gender")
            put("birthday", "1991-07-13")
            put("location", "Planted City")
            put("joined_at", "2011-03-05T10:20:30+00:00")
            put("time_zone", "Planted/Zone")
            put("is_supporter", true)
            put("email", "planted@mail.example")
            put("about", "")
            putJsonObject("anime_statistics") {
                put("num_items_watching", 31)
                put("num_items", 1234)
                put("num_days_watched", 567.89)
                put("mean_score", 7.65)
                put("num_episodes", 43210)
            }
            putJsonArray("favourites") { add(buildJsonObject { put("title", "planted favourite") }) }
        }.toString()

    private fun JsonObjectBuilder.node(
        id: Long,
        title: Title,
    ) {
        put("id", id)
        put("title", title.name)
        put("num_episodes", title.episodes)
        put("status", title.status)
        put("start_date", "2019-07-01")
    }

    private fun values(
        entry: Map<String, String>,
        token: String,
    ): JsonObject =
        buildJsonObject {
            put("status", entry.getValue("status"))
            put("score", entry.getValue("score").toInt())
            put("num_episodes_watched", entry.getValue("num_episodes_watched").toInt())
            put("is_rewatching", false)
            put("updated_at", entry.getValue("updated_at"))
            entry["start_date"]?.let { put("start_date", it) }
            entry["finish_date"]?.let { put("finish_date", it) }
            put("comments", "${entry["comments"].orEmpty()} $token")
        }

    private fun page(
        withValues: Boolean,
        status: String?,
        limit: Int,
        offset: Int,
        token: String,
    ): MockResponse {
        val stale = listStillShows.filter { it !in list }.associateWith { PERSONAL_ENTRY }
        val shown = (list + stale).toSortedMap().entries.filter { it.key !in listHides }
        val entries = shown.filter { status == null || it.value["status"] == status }
        val limit = minOf(limit, largestPage)
        val place = if (brokenNext) "page=" else "offset="
        val link = "https://api.myanimelist.net/v2/users/$USER_NAME/animelist?token=$token&limit=$limit&$place"
        val body =
            buildJsonObject {
                put(
                    "data",
                    buildJsonArray {
                        for ((id, entry) in entries.drop(offset).take(limit)) {
                            add(
                                buildJsonObject {
                                    putJsonObject("node") { node(id, known(id)) }
                                    if (withValues) put("list_status", values(entry, token))
                                },
                            )
                        }
                    },
                )
                putJsonObject("paging") {
                    if (offset > 0) put("previous", link + maxOf(0, offset - limit))
                    if (offset + limit < entries.size) put("next", link + (offset + limit))
                }
            }
        return answer(200, body.toString())
    }

    private fun known(id: Long): Title = titles[id] ?: Title("Listed $id", 24)

    private fun title(id: Long): MockResponse {
        val entry = list[id]
        if (entry == null && id !in titles) return answer(404, NOT_FOUND)
        val body =
            buildJsonObject {
                node(id, known(id))
                if (entry != null && tellsTheEntry) put("my_list_status", values(entry, "no token"))
            }
        return answer(200, body.toString())
    }

    private fun change(
        id: Long,
        method: String,
        form: Map<String, String>,
        token: String,
    ): MockResponse {
        if (method == "DELETE") {
            if (removalsDoNothing && id in list) return answer(200, "[]")
            list.remove(id) ?: return answer(404, NOT_FOUND)
            return answer(200, if (answersRemovalsInJson) "[]" else "")
        }
        val refused =
            form["score"]?.let { it.toInt() !in 0..10 } == true ||
                form["status"]?.let { it !in setOf("watching", "completed", "on_hold", "dropped", "plan_to_watch") } == true
        if (refused) return answer(400, """{"message":"Invalid value, user $user","error":"bad_request"}""")
        val entry = (list[id] ?: mapOf("status" to "watching", "score" to "0", "num_episodes_watched" to "0")).toMutableMap()
        entry["updated_at"] = CHANGED_AT
        for ((name, value) in form) {
            when {
                name == "num_watched_episodes" -> entry["num_episodes_watched"] = value
                name.endsWith("_date") && (value.isEmpty() || value.startsWith("0000")) -> entry.remove(name)
                else -> entry[name] = value
            }
        }
        list[id] = entry
        return answer(200, values(entry, token).toString())
    }

    private fun form(body: String): Map<String, String> =
        body.split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }

    companion object {
        const val ACCESS = "planted-access-token-51a7"
        const val REFRESH = "planted-refresh-token-c0de"
        const val USER_ID = 4815162L
        const val USER_NAME = "Owner_Name-9"
        const val PICTURE = "https://cdn.example/userimages/4815162.jpg"
        const val TITLE = 5L
        const val UPDATED_AT = "2016-06-07T08:09:10+00:00"
        const val CHANGED_AT = "2026-11-14T12:00:00+00:00"
        const val NOT_FOUND = """{"message":"","error":"not_found"}"""
        val LISTED = 101L..140L
        const val LAST_COMPLETED = 135L
        val PERSONAL_ENTRY =
            mapOf(
                "status" to "completed",
                "score" to "9",
                "num_episodes_watched" to "24",
                "start_date" to "2015-04-05",
                "finish_date" to "2015-06-21",
                "comments" to "a planted note",
                "updated_at" to UPDATED_AT,
            )

        // What no stored file may hold: the user's values, the tokens and the headers of an answer.
        val PLANTED =
            listOf(
                ACCESS,
                REFRESH,
                USER_NAME,
                "$USER_ID",
                PICTURE,
                "userimages",
                "planted",
                "Planted",
                "1991-07-13",
                "2011-03-05",
                "1234",
                "567.89",
                "7.65",
                "43210",
                "2015-04-05",
                "2015-06-21",
                "2016-06-07",
                "Bearer",
                "Cookie",
            )

        fun answer(
            status: Int,
            body: String,
            type: String = "application/json; charset=UTF-8",
        ): MockResponse =
            MockResponse
                .Builder()
                .code(status)
                .setHeader("Content-Type", type)
                .addHeader("Set-Cookie", "planted=cookie")
                .addHeader("X-Planted", "planted-header")
                .body(body)
                .build()
    }
}

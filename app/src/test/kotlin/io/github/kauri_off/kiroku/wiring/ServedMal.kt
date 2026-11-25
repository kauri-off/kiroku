package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.data.network.MalHosts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.rules.ExternalResource
import java.util.concurrent.CopyOnWriteArrayList

// A local server in MAL's place: a sign-in gets the reference's token answer, and the user's list is recorded pages.
class ServedMal : ExternalResource() {
    private val server = MockWebServer()

    // Every request in the order it arrived: the method, the path with its query, and the body of a write.
    val asked = CopyOnWriteArrayList<String>()

    // How many requests have arrived.
    val arrived = MutableStateFlow(0)

    // The pages of the user's list, from offset 0.
    @Volatile
    var pages: List<String> = publicPages

    // The answer to a change of the list; a test that expects none gets a server error with no body.
    @Volatile
    var write: (RecordedRequest) -> MockResponse = { answer(500, "") }

    @Volatile
    var manga = answer(404, "")

    val hosts: MalHosts get() = MalHosts(server.url("/v2/").toString(), server.url("/v1/oauth2/").toString())

    val writes: List<String> get() = asked.filter { it.startsWith("PATCH") || it.startsWith("DELETE") }

    override fun before() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = request.body?.utf8().orEmpty()
                    asked += "${request.method} ${request.target}${if (body.isEmpty()) "" else " $body"}"
                    arrived.update { it + 1 }
                    val path = request.url.encodedPath
                    return when {
                        path == "/v1/oauth2/token" -> answer(200, resource("authorization/token.json"))
                        path == "/v2/users/@me/animelist" -> {
                            val offset = request.url.queryParameter("offset")?.toInt() ?: 0
                            pages.getOrNull(offset / PAGE)?.let { answer(200, it) } ?: answer(404, recorded("title_unknown.404.json"))
                        }
                        path == "/v2/users/@me/mangalist" -> manga
                        path.endsWith("/my_list_status") -> write(request)
                        else -> answer(404, recorded("title_unknown.404.json"))
                    }
                }
            }
        server.start()
    }

    override fun after() = server.close()

    companion object {
        // Each recorded page holds this many entries.
        const val PAGE = 10

        // AniList's place beside it, where nothing answers: a test of MAL's list reaches no real host for airing.
        const val NO_ANILIST = "http://127.0.0.1:1/"
        const val NO_TENRAI = "http://127.0.0.1:1/v1/"

        fun recorded(name: String): String = resource("recorded/$name")

        val publicPages: List<String> = (1..3).map { recorded("list_public_$it.200.json") }

        // The ids of the titles on these pages, in order.
        fun ids(pages: List<String>): List<Long> =
            pages.flatMap { page ->
                val data = JSONObject(page).getJSONArray("data")
                (0 until data.length()).map { data.getJSONObject(it).getJSONObject("node").getLong("id") }
            }

        // A recorded page with one value of each named title's list status altered.
        fun altered(
            page: String,
            change: Map<Long, Pair<String, Any>>,
        ): String {
            val json = JSONObject(page)
            val data = json.getJSONArray("data")
            for (i in 0 until data.length()) {
                val entry = data.getJSONObject(i)
                val (key, value) = change[entry.getJSONObject("node").getLong("id")] ?: continue
                entry.getJSONObject("list_status").put(key, value)
            }
            return json.toString()
        }

        // A title's list status as a recorded page has it, served as a write's answer.
        fun listStatus(
            page: String,
            id: Long,
        ): JSONObject {
            val data = JSONObject(page).getJSONArray("data")
            return (0 until data.length())
                .map { data.getJSONObject(it) }
                .single { it.getJSONObject("node").getLong("id") == id }
                .getJSONObject("list_status")
        }

        fun answer(
            code: Int,
            body: String,
        ): MockResponse =
            MockResponse
                .Builder()
                .code(code)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .body(body)
                .build()

        private fun resource(path: String): String = checkNotNull(ServedMal::class.java.classLoader!!.getResource(path)) { path }.readText()
    }
}

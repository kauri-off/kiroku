package io.github.kauri_off.kiroku.data.sandbox

import io.github.kauri_off.kiroku.data.network.TestClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder
import java.time.Instant

internal fun recorded(name: String): String =
    checkNotNull(SandboxFixture::class.java.getResource("/mal/recorded/$name")) { name }.readText()

internal fun recordedTenrai(name: String): String =
    checkNotNull(SandboxFixture::class.java.getResource("/tenrai/recorded/$name")) { name }.readText()

internal fun printed(name: String): String =
    checkNotNull(SandboxFixture::class.java.getResource("/mal/authorization/$name")) { name }.readText()

internal fun poster(id: Long) = "https://example.invalid/kiroku/poster/$id.jpg"

// The sandbox's titles, as the app reads them from its assets.
internal val sandboxTitles: Map<Long, JsonObject> by lazy { SandboxTitles.read({ recorded("$it.200.json") }, ::poster) }

// A sandbox over [titles], by default the committed ones, starting with [starting], its public user's list [public] or
// its own, and the app's client and a signed-in session over it.
internal class SandboxFixture(
    private val titles: Map<Long, JsonObject>? = null,
    private val starting: () -> Map<Long, Listed> = { emptyMap() },
    private val public: () -> Map<Long, Listed>? = { null },
) : ExternalResource() {
    val folder = TemporaryFolder()
    val clock = TestClock(Instant.parse("2026-11-13T10:00:00Z"))
    val tokens by lazy { SandboxTokens(clock) }
    val list by lazy {
        SandboxList(titles ?: sandboxTitles, clock, starting = { starting() }, public = { public() ?: SandboxUsers.publicList(it) })
    }
    val mal by lazy { SandboxMal(list, tokens) }
    val client by lazy { SandboxClient(mal, clock = clock) }
    lateinit var signedIn: SandboxClient.SignedIn
    val session get() = signedIn.session
    val access get() = signedIn.access

    override fun before() {
        folder.create()
        signedIn = client.signedIn(folder.newFolder(), tokens)
    }

    override fun after() {
        runBlocking { signedIn.sessions.close() }
        folder.delete()
    }

    // An answer to a request made through the app's client, as its status and its body.
    fun ask(
        method: String,
        path: String,
        form: Map<String, String>? = null,
        bearer: String? = access,
        host: String = SandboxMal.Hosts.api,
    ): Pair<Int, String> {
        val body = form?.let { FormBody.Builder().apply { it.forEach { (k, v) -> add(k, v) } }.build() }
        val request =
            Request
                .Builder()
                .url(host + path)
                .method(method, body ?: if (method == "PATCH" || method == "POST") "".toRequestBody() else null)
                .apply { bearer?.let { header("Authorization", "Bearer $it") } }
                .build()
        return client.http.callFactory
            .newCall(request)
            .execute()
            .use { it.code to it.body.string() }
    }
}

// A JSON value as its shape alone: the keys of every object, and the type of every value, never the value.
internal fun shape(element: JsonElement): String =
    when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${it.key}:${shape(it.value)}" }
        is JsonArray -> if (element.isEmpty()) "[]" else "[${shape(element.first())}]"
        is JsonNull -> "null"
        is JsonPrimitive ->
            when {
                element.isString -> "string"
                element.booleanOrNull != null -> "boolean"
                element.longOrNull != null -> "integer"
                else -> "number"
            }
    }

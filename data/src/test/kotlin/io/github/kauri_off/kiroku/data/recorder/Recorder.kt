package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.bearer
import io.github.kauri_off.kiroku.data.session.refreshGrant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Clock

// One request of a recorder's list; [path] names a user only by a [RecordedUser]'s stand-in.
internal data class Ask(
    val name: String,
    val path: String,
    // Made up: the request is then made as a signed-in user.
    val accessToken: String? = null,
    // Made up: the request is then a refresh, posted to the sign-in host.
    val refreshToken: String? = null,
    // More than one: the whole list, read in so many pages.
    val pages: Int = 1,
) {
    // What its answers are stored as, each followed by the status and the kind of body.
    val files: List<String> get() = if (pages == 1) listOf(name) else (1..pages).map { "${name}_$it" }
}

// The users whose lists are recorded: what stands for the name in the repository, and where the build takes the name from.
internal enum class RecordedUser(
    val standIn: String,
    val property: String,
) {
    Public("public-user", "kiroku.recordPublicUser"),
    Private("private-user", "kiroku.recordPrivateUser"),
}

internal const val NO_CLIENT_ID = "no client id"

// The recorder ends here; its message holds nothing of a request.
internal class RecorderStop(
    message: String,
) : Exception(message)

internal fun recorderHttp(
    hosts: MalHosts,
    clientId: String,
): MalHttp =
    MalHttp(
        hosts = hosts,
        clientId = clientId,
        appVersion = "recorder",
        device =
            object : Device {
                override fun isOnline() = true

                override fun isMainThread() = false
            },
        clock = Clock.systemUTC(),
        unexpected = {},
    )

// Sends requests one at a time; stores each answer's status and JSON body, never a header or anything [say] is given.
internal class Recorder(
    private val http: MalHttp,
    private val users: Map<RecordedUser, String>,
    private val into: File,
    // Where a run's answers wait until the run has ended: a run that stops replaces nothing in [into].
    private val staging: File,
    private val say: (String) -> Unit,
    private val pause: () -> Unit,
) {
    private val sanitiser = Sanitiser(users.entries.associate { (user, name) -> name to user.standIn } + (http.clientId to "client-id"))

    var sent = 0
        private set

    // Stops at the first request that MAL refuses or does not answer.
    fun record(asks: List<Ask>) {
        if (http.clientId.isBlank()) throw RecorderStop(NO_CLIENT_ID)
        require(staging.canonicalFile != into.canonicalFile)
        staging.mkdirs()
        staging.listFiles()?.forEach { it.delete() }
        try {
            asks.forEach(::ask)
        } catch (stop: RecorderStop) {
            throw RecorderStop("${stop.message}\nNo stored answer was replaced; what this run was answered is in $staging")
        }
        keep()
    }

    private fun ask(ask: Ask) {
        val missing = RecordedUser.entries.firstOrNull { it.standIn in ask.path && users[it].isNullOrBlank() }
        when {
            missing != null -> say("${ask.name}: skipped, ${missing.property} is not set")
            ask.pages > 1 -> pages(ask)
            else -> store(ask, ask.name, request(ask, address(ask)))
        }
    }

    // The run's answers take the place of the earlier ones of their names.
    private fun keep() {
        into.mkdirs()
        for (answer in staging.listFiles().orEmpty().sortedBy { it.name }) {
            into.listFiles { earlier -> earlier.name.substringBefore('.') == answer.name.substringBefore('.') }?.forEach { it.delete() }
            Files.move(answer.toPath(), File(into, answer.name).toPath())
        }
        staging.delete()
    }

    private fun address(ask: Ask): HttpUrl {
        val host = if (ask.refreshToken == null) http.hosts.api else http.hosts.signIn
        return (host + users.entries.fold(ask.path) { path, (user, name) -> path.replace(user.standIn, name) }).toHttpUrl()
    }

    private fun request(
        ask: Ask,
        address: HttpUrl,
    ): Request {
        val request = Request.Builder().url(address)
        if (ask.accessToken != null) request.header(AUTHORIZATION, bearer(ask.accessToken))
        if (ask.refreshToken != null) {
            val form = FormBody.Builder().add("client_id", http.clientId)
            refreshGrant(ask.refreshToken).forEach(form::add)
            request.post(form.build())
        }
        return request.build()
    }

    private fun pages(ask: Ask) {
        val address = address(ask)
        val entries = count(ask, address) ?: return
        val size = (entries + ask.pages - 1) / ask.pages
        if (entries < ask.pages || size > LARGEST_PAGE) {
            say("${ask.name}: not recorded, the list cannot be read in ${ask.pages} pages")
            return
        }
        ask.files.forEachIndexed { page, file -> store(ask, file, request(ask, address.page(size, page * size))) }
    }

    // How many entries the list has, or null when MAL does not give it.
    private fun count(
        ask: Ask,
        address: HttpUrl,
    ): Int? {
        val whole = address.newBuilder().removeAllQueryParameters("fields").build()
        var entries = 0
        while (true) {
            val answer = send(ask.name, request(ask, whole.page(LARGEST_PAGE, entries)))
            val page = (if (answer.status == 200) json(answer.body)?.get("data") else null) as? JsonArray
            if (page == null) {
                say("${ask.name}: not recorded, the list could not be counted (${answer.status})")
                refused(ask, ask.name, answer.status)
                return null
            }
            entries += page.size
            if (page.size < LARGEST_PAGE || entries > LARGEST_PAGE * ask.pages) return entries
        }
    }

    private fun HttpUrl.page(
        size: Int,
        offset: Int,
    ): HttpUrl = newBuilder().setQueryParameter("limit", "$size").setQueryParameter("offset", "$offset").build()

    private fun json(body: String): JsonObject? =
        try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }

    // A body that is not JSON could hold a name in a form the sanitiser does not know: only what kind it was is stored.
    private fun store(
        ask: Ask,
        file: String,
        request: Request,
    ) {
        val answer = send(file, request)
        val kind = answer.type ?: "no content type"
        val json = sanitiser.json(answer.body)
        File(staging, "$file.${answer.status}.${if (json != null) "json" else "txt"}")
            .writeText(json ?: sanitiser.plain("${answer.status}, $kind\n"))
        say("$file: ${answer.status}, $kind, ${answer.body.length} characters")
        refused(ask, file, answer.status)
    }

    // A refusal is rate limiting, except for the list of a user of the build's, which may be private.
    private fun refused(
        ask: Ask,
        file: String,
        status: Int,
    ) {
        val mayBePrivate = RecordedUser.entries.any { it.standIn in ask.path }
        if (status == 429 || (status == 403 && !mayBePrivate)) throw RecorderStop("$file: MAL refuses requests ($status), stopped")
    }

    private class Answer(
        val status: Int,
        val type: String?,
        val body: String,
    )

    private fun send(
        file: String,
        request: Request,
    ): Answer {
        if (sent++ > 0) pause()
        try {
            return http.callFactory
                .newCall(request)
                .execute()
                .use { Answer(it.code, it.header("Content-Type"), it.body.string()) }
        } catch (e: IOException) {
            throw RecorderStop("$file: no answer (${e.javaClass.simpleName}), stopped")
        }
    }

    private companion object {
        const val LARGEST_PAGE = 1000
    }
}

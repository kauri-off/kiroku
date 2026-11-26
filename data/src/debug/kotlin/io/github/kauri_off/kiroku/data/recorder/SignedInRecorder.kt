package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.data.manga.MangaMutationMethod
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.NotSentAgain
import io.github.kauri_off.kiroku.data.network.RequestOwnerEnded
import io.github.kauri_off.kiroku.data.network.Sends
import io.github.kauri_off.kiroku.data.network.bearer
import io.github.kauri_off.kiroku.data.session.Attempt
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

// The title a recording is made on, as MAL names it.
data class RecordedTitle(
    val id: Long,
    val name: String,
    val episodes: Int,
)

// Why the recorder did not start, stopped, or could not take its title off the list.
sealed interface Halt {
    data object OnTheList : Halt

    data object NotFinished : Halt

    data object TooFewEpisodes : Halt

    data object NoSuchTitle : Halt

    data object NotSignedIn : Halt

    data object NoAnswer : Halt

    // The device's own store failed.
    data object Storage : Halt

    // Another user is signed in than the one whose list the title was put on.
    data object AnotherAccount : Halt

    // MAL answered the removal, and still shows the title on the list.
    data object StillOnTheList : Halt

    // MAL limits requests.
    data class Refused(
        val status: Int,
    ) : Halt

    data class Unexpected(
        val status: Int,
    ) : Halt
}

// What became of the user's list.
enum class ListAfter {
    // Nothing was written.
    Untouched,

    // The recorder's title is off the list, which holds the titles it held before the recording.
    Same,

    // The title is off the list, which holds other titles than before the recording.
    Differs,

    // The title is off the list, which was not compared.
    Removed,

    // The title may still be on the list.
    Left,
}

sealed interface Recording {
    data object Idle : Recording

    data class Checking(
        val title: Long,
    ) : Recording

    // A recording may be made on the title: nothing is written before [SignedInRecorder.record] is called with it.
    data class Checked(
        val title: RecordedTitle,
    ) : Recording

    data class Running(
        val title: RecordedTitle,
        val answers: Int,
        val of: Int,
    ) : Recording

    data class Removing(
        val title: RecordedTitle,
    ) : Recording

    data object Reading : Recording

    data class ReadRecorded(
        val answers: Int,
        val folder: File,
    ) : Recording

    data class ReadStopped(
        val at: String,
        val halt: Halt,
        val answers: Int,
        val folder: File?,
    ) : Recording

    data class NotStarted(
        val halt: Halt,
    ) : Recording

    data class Recorded(
        val title: RecordedTitle,
        val answers: Int,
        val folder: File,
        val list: ListAfter,
    ) : Recording

    // The run ended at the request [at]; its answers wait in [folder] and replaced none.
    data class Stopped(
        val title: RecordedTitle,
        val at: String,
        val halt: Halt,
        val answers: Int,
        val folder: File,
        val list: ListAfter,
    ) : Recording

    // The title of a recording that did not take it off the list, and whether it is off now.
    data class Leftover(
        val title: RecordedTitle,
        val list: ListAfter,
        val halt: Halt?,
    ) : Recording
}

// Where the answers go, each as MAL sent it: those of a run that ended, and those of the run in hand.
class RecorderFolders(
    val recorded: File,
    val run: File,
)

// The recorder of a source; [journal] and [folders] are asked for off the main thread, the folders only by a run.
fun signedInRecorder(
    session: Lazy<MalSession>,
    http: MalHttp,
    journal: () -> File,
    folders: () -> RecorderFolders,
    privateReads: () -> File,
    scope: CoroutineScope,
    privateMutations: (() -> File)? = null,
): SignedInRecorder = SignedInRecorder(session, http, journal, folders, privateReads, scope, privateMutations = privateMutations)

class SignedInRecorder internal constructor(
    private val session: Lazy<MalSession>,
    private val http: MalHttp,
    journalFile: () -> File,
    folders: () -> RecorderFolders,
    privateReads: () -> File,
    scope: CoroutineScope,
    private val pause: suspend () -> Unit = { delay(PAUSE_MILLIS) },
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val writePrivateFile: (File, String) -> Unit = { file, text -> file.writeText(text) },
    privateMutations: (() -> File)? = null,
) {
    private val journal by lazy { Journal(journalFile()) }
    private val files by lazy(folders)
    private val privateRoot by lazy(privateReads)
    private val work = scope + dispatcher
    private val busy = Mutex()
    private val shown = MutableStateFlow<Recording>(Recording.Idle)
    val state: StateFlow<Recording> = shown.asStateFlow()
    val manga = privateMutations?.let { MangaCaptureRecorder(session, work, it, writePrivateFile) }

    // Of the work in hand, which holds [busy].
    private var sent = 0
    private var stored = 0
    private var running: RecordedTitle? = null

    internal val started: Job =
        work.launch {
            busy.withLock {
                manga?.initialize()
                journal.read()?.let { shown.value = leftover(it) }
            }
        }

    fun armManga(
        id: MangaId,
        method: MangaMutationMethod,
        contains: suspend (SignInId, MangaId) -> Boolean,
    ): Job {
        val owner = if (session.isInitialized()) session.value.signedInOwner() else null
        return act {
            if (journal.read() == null && shown.value != Recording.Reading && shown.value !is Recording.Checked) {
                manga?.arm(owner, id, method, contains)
            }
        }
    }

    // Asks MAL for the title and nothing else. Refuses one a recording cannot be made on, and any while a title is left.
    fun check(title: Long): Job =
        act {
            val left = journal.read()
            if (left == null) shown.value = Recording.Checking(title)
            shown.value = if (left == null) checked(title) else Recording.Leftover(left.title, ListAfter.Left, null)
        }

    // Runs only on the title that was checked last and is still shown for confirmation.
    fun record(title: RecordedTitle): Job = act { if (shown.value == Recording.Checked(title)) shown.value = ran(title) }

    fun recordReads(capture: ReadCapture): Job {
        val owner = if (session.isInitialized()) session.value.signedInOwner() else null
        return act {
            val left = journal.read()
            shown.value =
                if (left == null) {
                    shown.value = Recording.Reading
                    readCapture(capture, owner)
                } else {
                    Recording.Leftover(left.title, ListAfter.Left, null)
                }
        }
    }

    // One more try at taking off the list what a recording left there.
    fun remove(): Job = act { journal.read()?.let { shown.value = leftover(it) } }

    // Back to the start, unless work is running or a title may still be on the list.
    fun dismiss() = shown.update { if (it.settled) Recording.Idle else it }

    private val Recording.settled: Boolean
        get() =
            when (this) {
                is Recording.Checked, is Recording.NotStarted, is Recording.ReadRecorded, is Recording.ReadStopped -> true
                is Recording.Recorded -> list != ListAfter.Left
                is Recording.Stopped -> list != ListAfter.Left
                is Recording.Leftover -> list != ListAfter.Left
                else -> false
            }

    // One piece of work at a time; a call made while another runs does nothing.
    private fun act(block: suspend () -> Unit): Job =
        work.launch {
            if (!busy.tryLock()) return@launch
            try {
                if (manga?.active != true) block()
            } finally {
                busy.unlock()
            }
        }

    private fun begin(title: RecordedTitle?) {
        sent = 0
        stored = 0
        running = title
    }

    private suspend fun checked(id: Long): Recording =
        try {
            begin(null)
            Recording.Checked(recordable(ask(CHECK, entry(id, MalFields.OWN_ENTRY), kept = false, expected = ::knownOrNot), id))
        } catch (halted: Halted) {
            Recording.NotStarted(halted.halt)
        }

    private suspend fun readCapture(
        capture: ReadCapture,
        owner: SessionOwner?,
    ): Recording {
        if (owner == null) return Recording.NotStarted(Halt.NotSignedIn)
        begin(null)
        val pending =
            try {
                session.value.withOwner(owner) {
                    val directory = Files.createDirectories(File(privateRoot, capture.key).toPath())
                    Files.createDirectory(directory.resolve("run-${UUID.randomUUID()}")).toFile()
                } ?: return Recording.NotStarted(Halt.AnotherAccount)
            } catch (_: IOException) {
                return Recording.ReadStopped(RUN, Halt.Storage, 0, null)
            }
        return try {
            capturePages(capture, owner, pending)
            val finished = File(pending.parentFile, "capture-${pending.name.removePrefix("run-")}")
            val kept =
                session.value.withOwner(owner) {
                    filed(RUN) { Files.move(pending.toPath(), finished.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                }
            if (kept == null) throw Halted(Halt.AnotherAccount, RUN)
            Recording.ReadRecorded(stored, finished)
        } catch (halted: Halted) {
            Recording.ReadStopped(halted.at, halted.halt, stored, pending)
        }
    }

    private suspend fun capturePages(
        capture: ReadCapture,
        owner: SessionOwner,
        pending: File,
    ) {
        var offset = 0
        repeat(capture.pages) { index ->
            val name = capture.answer(index)
            val answer = send(name, get("${capture.request}&offset=$offset"), owner)
            storePrivate(name, answer.kept, pending)
            expect(name, answer.status, ::ok)
            val next = nextReadOffset(capture, name, answer, offset) ?: return
            if (index == capture.pages - 1) {
                if (capture == ReadCapture.Suggestions) return
                throw Halted(Halt.Unexpected(answer.status), name)
            }
            offset = next
        }
    }

    private fun nextReadOffset(
        capture: ReadCapture,
        name: String,
        answer: Answer,
        offset: Int,
    ): Int? {
        val invalid = Halted(Halt.Unexpected(answer.status), name)
        val page = answer.page ?: throw invalid
        val entries = page["data"] as? JsonArray ?: throw invalid
        val paging = page["paging"]?.takeUnless { it is JsonNull }
        if (paging == null) {
            if (capture == ReadCapture.MangaList) throw invalid
            return null
        }
        val next = (paging as? JsonObject ?: throw invalid)["next"]?.takeUnless { it is JsonNull } ?: return null
        return (next as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.toHttpUrlOrNull()
            ?.queryParameterValues("offset")
            ?.singleOrNull()
            ?.toIntOrNull()
            ?.takeIf { it > offset && (capture != ReadCapture.MangaList || it - offset == entries.size) } ?: throw invalid
    }

    private suspend fun ran(checked: RecordedTitle): Recording {
        begin(checked)
        shown.value = Recording.Running(checked, 0, SignedInAnswers.size)
        var written = false
        try {
            if (!emptied(files.run)) throw Halted(Halt.Storage, RUN)
            lifetime()
            val user = profile()
            val before = ownList()
            val absent = ask(ABSENT, entry(checked.id, MalFields.OWN_ENTRY), expected = ::knownOrNot)
            val title = recordable(absent, checked.id)
            if (title.id in before) throw Halted(Halt.OnTheList, ABSENT)
            filed(JOURNAL) { journal.write(Journal.Left(title, user)) }
            written = true
            changes(title)
            filed(RUN) { keep(files.run, files.recorded) }
            return Recording.Recorded(title, stored, files.recorded, compared(title, user, before))
        } catch (halted: Halted) {
            val left = journal.read()
            val list =
                when {
                    !written -> ListAfter.Untouched
                    left == null -> ListAfter.Removed
                    // MAL answers, so the title can be taken off now; otherwise that waits for the next start.
                    (halted.halt is Halt.Unexpected || halted.halt is Halt.Storage) && removed(left) == null -> ListAfter.Removed
                    else -> ListAfter.Left
                }
            return Recording.Stopped(checked, halted.at, halted.halt, stored, files.run, list)
        }
    }

    // The fixed list of changes, each read back; the title is on the list from the first to the removal.
    private suspend fun changes(title: RecordedTitle) {
        for ((index, write) in OwnTitleWrites.withIndex()) {
            val adds = index == 0
            ask(write.name, patch(title.id, write.form(title.episodes)), expected = if (adds) ::ok else ::answered)
            ask("${write.name}_read", entry(title.id, READ_BACK))
        }
        ask("entry_full", entry(title.id, MalFields.OWN_ENTRY))
        ask(REMOVED, delete(title.id))
        offTheList(ask("removed_read", entry(title.id, READ_BACK), expected = ::knownOrNot), title.id, "removed_read")
        journal.delete()
        ask("removed_again", delete(title.id), expected = ::knownOrNot)
    }

    private suspend fun leftover(left: Journal.Left): Recording {
        begin(null)
        shown.value = Recording.Removing(left.title)
        val halt = removed(left)
        return Recording.Leftover(left.title, if (halt == null) ListAfter.Removed else ListAfter.Left, halt)
    }

    // Null once the title is off the list of the user the recording was made as; another user's list is not touched.
    private suspend fun removed(left: Journal.Left): Halt? =
        try {
            val user = ask(USER, get("users/@me?fields=id"), kept = false)
            if (userOf(user) != left.user) throw Halted(Halt.AnotherAccount, USER)
            ask(REMOVED, delete(left.title.id), kept = false, expected = ::knownOrNot)
            offTheList(ask(READ, entry(left.title.id, READ_BACK), kept = false, expected = ::knownOrNot), left.title.id, READ)
            journal.delete()
            null
        } catch (halted: Halted) {
            halted.halt
        }

    // The journal goes only on MAL's own answer for this title without an entry, or on MAL's word that it knows no such title.
    private fun offTheList(
        read: Answer,
        id: Long,
        at: String,
    ) {
        val title = read.page
        if (read.status == NOT_FOUND && title?.text("error") == UNKNOWN_TITLE) return
        if (read.status != OK || title?.number("id") != id) throw Halted(Halt.Unexpected(read.status), at)
        if (title["my_list_status"].holdsValues) throw Halted(Halt.StillOnTheList, at)
    }

    private suspend fun lifetime() {
        val seconds = session.value.accessTokenLifetime() ?: return
        val sanitiser = Sanitiser(emptyMap())
        store(TOKEN_GRANTED, sanitiser.kept(TOKEN_GRANTED, OK, null, sanitiser.json("""{"expires_in":$seconds}""")))
    }

    // Who is signed in, as the journal names them.
    private suspend fun profile(): String {
        val answer = ask(PROFILE, get("users/@me?fields=${MalFields.PROFILE}"))
        return userOf(answer) ?: throw Halted(Halt.Unexpected(answer.status), PROFILE)
    }

    private fun userOf(answer: Answer): String? =
        (answer.page?.get("id") as? JsonPrimitive)?.content?.let { id ->
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        }

    // Every title on the list, and the list's first, middle and last third as pages.
    private suspend fun ownList(): Set<Long> {
        val titles = titlesOnTheList()
        val size = ((titles.size + PAGES - 1) / PAGES).coerceIn(1, LARGEST_PAGE)
        repeat(PAGES) { page ->
            val third = get("$OWN_LIST&sort=anime_title&limit=$size&offset=${page * size}")
            ask("own_list_${page + 1}", third)
        }
        return titles
    }

    // Read whole, adult titles included, and not stored: page after page until MAL names no next one.
    private suspend fun titlesOnTheList(): Set<Long> {
        val titles = mutableSetOf<Long>()
        var offset = 0L
        repeat(MOST_PAGES) {
            val answer = ask(COUNT, get("users/@me/animelist?nsfw=true&limit=$LARGEST_PAGE&offset=$offset"), kept = false)
            val page = answer.page
            val entries = page?.get("data") as? JsonArray ?: throw Halted(Halt.Unexpected(answer.status), COUNT)
            entries.mapTo(titles) { entry ->
                ((entry as? JsonObject)?.get("node") as? JsonObject)?.number("id") ?: throw Halted(Halt.Unexpected(answer.status), COUNT)
            }
            val next = (page["paging"] as? JsonObject)?.get("next")?.takeUnless { it is JsonNull } ?: return titles
            // Of MAL's link only the place where the next page starts is taken; a link that does not go on ends the check.
            val start =
                (next as? JsonPrimitive)
                    ?.content
                    ?.toHttpUrlOrNull()
                    ?.queryParameter("offset")
                    ?.toLongOrNull()
            if (start == null || start <= offset) throw Halted(Halt.Unexpected(answer.status), COUNT)
            offset = start
        }
        throw Halted(Halt.Unexpected(OK), COUNT)
    }

    // A title MAL's list still holds is journalled again, so that it can be taken off.
    private suspend fun compared(
        title: RecordedTitle,
        user: String,
        before: Set<Long>,
    ): ListAfter {
        val after =
            try {
                titlesOnTheList()
            } catch (_: Halted) {
                return ListAfter.Removed
            }
        if (title.id !in after) return if (after == before) ListAfter.Same else ListAfter.Differs
        try {
            journal.write(Journal.Left(title, user))
        } catch (_: IOException) {
            return ListAfter.Left
        }
        return ListAfter.Left
    }

    // The title of [answer] when a recording may be made on it: MAL knows it, it has ended, and it is not on the list.
    private fun recordable(
        answer: Answer,
        id: Long,
    ): RecordedTitle {
        if (answer.status == NOT_FOUND) throw Halted(Halt.NoSuchTitle, ABSENT)
        val title = answer.page
        val name = title?.text("title")
        val episodes = title?.number("num_episodes")
        if (title?.number("id") != id || name == null || episodes == null) throw Halted(Halt.Unexpected(answer.status), ABSENT)
        val halt =
            when {
                title["my_list_status"].holdsValues -> Halt.OnTheList
                title.text("status") != FINISHED -> Halt.NotFinished
                episodes < FEWEST_EPISODES -> Halt.TooFewEpisodes
                else -> return RecordedTitle(id, name, episodes.toInt())
            }
        throw Halted(halt, ABSENT)
    }

    private val JsonElement?.holdsValues: Boolean
        get() =
            when (this) {
                null, is JsonNull -> false
                is JsonObject -> isNotEmpty()
                is JsonArray -> isNotEmpty()
                is JsonPrimitive -> true
            }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun address(path: String) = (http.hosts.api + path).toHttpUrl()

    private fun get(path: String) = Request.Builder().url(address(path))

    private fun entry(
        id: Long,
        fields: String,
    ) = get("anime/$id?fields=$fields")

    private fun patch(
        id: Long,
        form: Map<String, String>,
    ) = Request.Builder().url(address("anime/$id/my_list_status")).patch(FormBody.Builder().apply { form.forEach(::add) }.build())

    private fun delete(id: Long) = Request.Builder().url(address("anime/$id/my_list_status")).delete()

    private class Answer(
        val status: Int,
        val kept: Kept,
        val page: JsonObject? = null,
    )

    // The run ends here, at the request [at].
    private class Halted(
        val halt: Halt,
        val at: String,
    ) : Exception()

    // Stores the answer first, so a run that stops holds what stopped it; MAL's refusal and an answer not [expected] stop it.
    private suspend fun ask(
        name: String,
        request: Request.Builder,
        kept: Boolean = true,
        expected: (Int) -> Boolean = ::ok,
    ): Answer {
        val answer = send(name, request)
        if (kept) store(name, answer.kept)
        expect(name, answer.status, expected)
        return answer
    }

    private fun expect(
        name: String,
        status: Int,
        expected: (Int) -> Boolean,
    ) {
        if (status in REFUSALS) throw Halted(Halt.Refused(status), name)
        if (!expected(status)) throw Halted(Halt.Unexpected(status), name)
    }

    // One request at a time, a pause between two. The session gives the token, and a rejected one goes back to it.
    private suspend fun send(
        name: String,
        request: Request.Builder,
        owner: SessionOwner? = null,
    ): Answer {
        if (sent++ > 0) pause()
        val outcome =
            session.value.authorised(owner?.id) { accessToken ->
                try {
                    request.header(AUTHORIZATION, bearer(accessToken))
                    val call =
                        if (owner == null) {
                            http.callFactory.newCall(request.build())
                        } else {
                            request.tag(Sends::class.java, Sends(1, maySendRead = { session.value.isCurrentOwner(owner) }))
                            http.newCallWithoutRedirects(request.build())
                        }
                    call.execute().use { response ->
                        if (owner != null && !session.value.isCurrentOwner(owner)) {
                            return@use Attempt.Answered(Outcome.Failure(DataError.ListEnded))
                        }
                        if (owner != null && response.code in 300..399) return@use statusOnly(name, response.code)
                        if (response.code == UNAUTHORIZED) return@use Attempt.Rejected
                        val body =
                            try {
                                response.body.string()
                            } catch (unread: IOException) {
                                if (owner == null) throw unread
                                return@use statusOnly(name, response.code)
                            }
                        val sanitiser = Sanitiser(mapOf(accessToken to "access-token", http.clientId to "client-id"))
                        val answer =
                            if (owner == null) {
                                Answer(response.code, sanitiser.asSent(name, response.code, body), json(body))
                            } else {
                                val content = privateReadJson(body)
                                Answer(response.code, sanitiser.privateAnswer(name, response.code, content), content as? JsonObject)
                            }
                        Attempt.Answered(Outcome.Success(answer))
                    }
                } catch (_: RequestOwnerEnded) {
                    Attempt.Answered(Outcome.Failure(DataError.ListEnded))
                } catch (limited: NotSentAgain) {
                    val status = limited.status
                    if (owner != null && status != null) {
                        statusOnly(name, status)
                    } else {
                        Attempt.Answered(Outcome.Failure(DataError.NoConnection))
                    }
                } catch (_: IOException) {
                    Attempt.Answered(Outcome.Failure(DataError.NoConnection))
                }
            }
        return when (outcome) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure ->
                throw Halted(
                    when (outcome.error) {
                        DataError.SessionExpired -> Halt.NotSignedIn
                        DataError.StorageError -> Halt.Storage
                        DataError.ListEnded -> Halt.AnotherAccount
                        else -> Halt.NoAnswer
                    },
                    name,
                )
        }
    }

    private fun statusOnly(
        name: String,
        status: Int,
    ): Attempt<Answer> = Attempt.Answered(Outcome.Success(Answer(status, Kept("$name.$status.txt", "$status\n"))))

    private fun store(
        name: String,
        kept: Kept,
    ) {
        filed(name) { File(files.run, kept.file).writeText(kept.text) }
        stored++
        running?.let { shown.value = Recording.Running(it, stored, SignedInAnswers.size) }
    }

    private fun storePrivate(
        name: String,
        kept: Kept,
        pending: File,
    ) {
        filed(name) {
            storePrivateAnswer(pending, kept, writePrivateFile)
        }
        stored++
    }

    private inline fun filed(
        at: String,
        write: () -> Unit,
    ) = try {
        write()
    } catch (_: IOException) {
        throw Halted(Halt.Storage, at)
    }

    private companion object {
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        val REFUSALS = setOf(403, 429)
        const val PAGES = 3
        const val LARGEST_PAGE = 1000
        const val MOST_PAGES = 50
        const val FEWEST_EPISODES = 3
        const val FINISHED = "finished_airing"
        const val UNKNOWN_TITLE = "not_found"
        const val OWN_LIST = "users/@me/animelist?fields=${MalFields.LIST_ENTRY}&nsfw=true"
        const val READ_BACK = "id,num_episodes,my_list_status{${MalFields.LIST_VALUES}}"
        const val PROFILE = "profile"
        const val ABSENT = "entry_absent"
        const val REMOVED = "removed"

        // Requests and steps that are not stored, as a stop names them.
        const val CHECK = "check"
        const val COUNT = "count"
        const val USER = "user"
        const val READ = "read"
        const val JOURNAL = "journal"
        const val RUN = "files"
    }
}

private fun ok(status: Int) = status == 200

private fun knownOrNot(status: Int) = status == 200 || status == 404

// MAL may refuse the value: its refusal is what the request records.
private fun answered(status: Int) = status == 200 || status in 400..499

private fun json(body: String): JsonObject? =
    try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

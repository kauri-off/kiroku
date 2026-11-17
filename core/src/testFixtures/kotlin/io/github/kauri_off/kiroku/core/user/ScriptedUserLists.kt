package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// A source of other users' lists that answers from the scripts it holds, one request a part, each taking [latency].
class ScriptedUserLists(
    @Volatile var latency: Duration = 1.seconds,
    // How the source takes names.
    private val same: (String, String) -> Boolean = { a, b -> a.equals(b, ignoreCase = true) },
) : RemoteUserLists {
    // A user's list in parts, and how the request after the last of them ends when it is not the list's end.
    class Script(
        val parts: List<UserListPart>,
        val failure: DataError? = null,
    )

    private val scripts = ConcurrentHashMap<String, Script>()

    // The name of every request received, answered or not.
    val asked = CopyOnWriteArrayList<String>()

    // Every request fails so.
    @Volatile
    var failure: DataError? = null

    // Thrown by every request, as a client that fails to keep the port's word would.
    @Volatile
    var throwing: Exception? = null

    // What the source has of [name] from the next read on; a name with no script is nobody's.
    fun has(
        name: String,
        parts: List<UserListPart>,
        failure: DataError? = null,
    ) {
        scripts[name] = Script(parts, failure)
    }

    fun private(name: String) = has(name, emptyList(), DataError.PrivateList)

    override suspend fun read(
        name: String,
        part: suspend (UserListPart) -> Unit,
    ): Outcome<Unit> {
        val script = scripts.entries.firstOrNull { same(it.key, name) }?.value ?: Script(emptyList(), DataError.UnknownUser)
        for (given in script.parts) {
            request(name)?.let { return Outcome.Failure(it) }
            part(given)
        }
        val failure = script.failure ?: return Outcome.Success(Unit)
        return Outcome.Failure(request(name) ?: failure)
    }

    private suspend fun request(name: String): DataError? {
        asked += name
        delay(latency)
        throwing?.let { throw it }
        return failure
    }

    override fun sameUser(
        a: String,
        b: String,
    ): Boolean = same(a, b)
}

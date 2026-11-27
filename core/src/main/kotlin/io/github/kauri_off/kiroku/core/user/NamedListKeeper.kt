package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

// Other users' lists over their source: one read for every screen, held in memory for a few users (section 7.1).
internal class NamedListKeeper<E, Id>(
    private val source: suspend (String, suspend (NamedListPart<E>) -> Unit) -> Outcome<Unit>,
    private val sameUser: (String, String) -> Boolean,
    private val id: (E) -> Id,
    private val scope: CoroutineScope,
    // Hears of what nobody expected, anything the source throws, once the read that met it has ended.
    private val unexpected: (Throwable) -> Unit,
    // How many users' lists are held at most, beyond those a screen waits for.
    private val held: Int,
) {
    // A read under way; it ends with no outcome when it was stopped or its user forgotten.
    private class Read(
        val end: CompletableDeferred<Outcome<Unit>?> = CompletableDeferred(),
    ) {
        lateinit var job: Job
    }

    private class User<E>(
        val name: String,
    ) {
        // None once the user was forgotten.
        val state = MutableStateFlow<NamedListState<E>?>(NamedListState.Reading(emptyList(), null, null))

        // The screens that observe it and the calls that wait for its read; a read is under way only while there is one.
        var waiting = 0
        var read: Read? = null
        var shown = 0L
    }

    // Held for every change to the users and to a user's fields, never while the source is asked.
    private val lock = Any()
    private val users = mutableListOf<User<E>>()
    private var shows = 0L

    init {
        require(held > 0)
        check(scope.isActive) { STOPPED }
    }

    // How many users are held now.
    internal val holding: Int get() = synchronized(lock) { users.size }

    fun observeList(name: String): Flow<NamedListState<E>> =
        flow {
            while (true) {
                val (user, read) =
                    synchronized(lock) { waitFor(name).let { it to if (it.read == null && it.state.value.owed) read(it) else null } }
                read?.job?.start()
                try {
                    emitAll(user.state.transformWhile { state -> state?.let { emit(it) } != null })
                } finally {
                    leave(user)
                }
            }
        }

    suspend fun refreshList(name: String): Outcome<Unit> {
        while (true) {
            val (user, read) =
                synchronized(lock) { waitFor(name).let { it to (it.read?.takeUnless { read -> read.end.isCompleted } ?: read(it)) } }
            read.job.start()
            try {
                read.end.await()?.let { return it }
            } finally {
                leave(user)
            }
        }
    }

    // A sign-out: nothing of anyone's list stays, and a screen still waiting for one has it read anew.
    fun forget() {
        synchronized(lock) { users.toList().forEach(::drop) }
    }

    private fun waitFor(name: String): User<E> {
        check(scope.isActive) { STOPPED }
        val user = users.firstOrNull { sameUser(it.name, name) } ?: User<E>(name).also { users += it }
        user.shown = ++shows
        user.waiting++
        trim()
        return user
    }

    // A read nobody waits for stops there, so no list is ever put together from parts read at two times.
    private fun leave(user: User<E>) {
        synchronized(lock) {
            user.waiting--
            user.shown = ++shows
            val read = user.read
            if (user.waiting == 0 && read != null) {
                release(user, read, failed = false)
                read.job.cancel()
            }
            trim()
        }
    }

    // Past the bound the user shown longest ago is forgotten, never one a screen waits for.
    private fun trim() {
        while (users.size > held) drop(users.filter { it.waiting == 0 }.minByOrNull { it.shown } ?: return)
    }

    private fun drop(user: User<E>) {
        users -= user
        user.read?.let {
            user.read = null
            it.job.cancel()
            it.end.complete(null)
        }
        user.state.value = null
    }

    // The list held stays while it is read again; else the read alone shows. Started by its caller, outside the lock.
    private fun read(user: User<E>): Read {
        val read = Read()
        read.job = scope.launch(start = CoroutineStart.LAZY) { run(user, read) }
        user.read = read
        user.state.update { before ->
            when (before) {
                is NamedListState.Whole -> before.copy(again = UserListProgress(0, null))
                else -> NamedListState.Reading(emptyList(), null, before?.user)
            }
        }
        // A job that ended while the read was still its user's own ended by no doing of the keeper: the read failed.
        read.job.invokeOnCompletion {
            val lost = synchronized(lock) { (user.read === read).also { if (it) release(user, read, failed = true) } }
            read.end.complete(if (lost && scope.isActive) Outcome.Failure(DataError.ServerError) else null)
        }
        return read
    }

    // A read that ends with no outcome leaves the list that was held, else none: owed again, or failed.
    private fun release(
        user: User<E>,
        read: Read,
        failed: Boolean,
    ) {
        user.finish(read) { before ->
            when {
                before is NamedListState.Whole -> before.copy(again = null)
                failed -> NamedListState.None(DataError.ServerError, before.user)
                else -> NamedListState.Reading(emptyList(), null, before.user)
            }
        }
    }

    // One part at a time; a title the source gives twice is on the list once.
    private suspend fun run(
        user: User<E>,
        read: Read,
    ) {
        var entries = emptyList<E>()
        var total: Int? = null
        var described: UserProfile? = null
        var thrown: Throwable? = null
        val titles = HashSet<Id>()
        val end =
            try {
                source(user.name) { part ->
                    currentCoroutineContext().ensureActive()
                    val new = part.entries.filter { titles.add(id(it)) }
                    if (new.isNotEmpty()) entries = entries + new
                    total = part.total ?: total
                    described = part.user ?: described
                    user.during(read) { before ->
                        when (before) {
                            is NamedListState.Whole -> before.copy(again = UserListProgress(entries.size, total))
                            else -> NamedListState.Reading(entries, total, described ?: before.user)
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                Outcome.Failure(if (e is IOException) DataError.NoConnection else DataError.ServerError.also { thrown = e })
            }
        val error = (end as? Outcome.Failure)?.error
        // A whole list and the user's own answer replace what was held; any other failure leaves a list that was held.
        synchronized(lock) {
            user.finish(read) { before ->
                when {
                    error == null -> NamedListState.Whole(entries, described ?: before.user)
                    error == DataError.UnknownUser -> NamedListState.None(error)
                    before is NamedListState.Whole && !error.ofUser -> before.copy(again = null)
                    else -> NamedListState.None(error, described ?: before.user)
                }
            } ?: return
        }
        read.end.complete(end)
        thrown?.let(unexpected)
    }

    private fun User<E>.finish(
        read: Read,
        change: (NamedListState<E>) -> NamedListState<E>,
    ): NamedListState<E>? {
        val before = state.value?.takeIf { this.read === read } ?: return null
        this.read = null
        return change(before).also { state.value = it }
    }

    // Changes the state for the read that is the user's own; none for one that was stopped or whose user was forgotten.
    private fun User<E>.during(
        read: Read,
        change: (NamedListState<E>) -> NamedListState<E>,
    ): NamedListState<E>? =
        synchronized(lock) {
            val before = state.value?.takeIf { this.read === read } ?: return null
            change(before).also { state.value = it }
        }

    // A list that was never read whole, or whose read failed, is read when a screen comes to wait for it.
    private val NamedListState<E>?.owed: Boolean
        get() = this is NamedListState.Reading || (this is NamedListState.None && !error.ofUser)

    companion object {
        private const val STOPPED = "The scope of other users' lists has ended"
    }
}

internal data class NamedListPart<E>(
    val entries: List<E>,
    val total: Int? = null,
    val user: UserProfile? = null,
)

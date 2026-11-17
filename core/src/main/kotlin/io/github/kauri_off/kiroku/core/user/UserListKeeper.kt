package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.repository.OwnProfile
import io.github.kauri_off.kiroku.core.repository.UserLists
import io.github.kauri_off.kiroku.core.repository.UserRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

// Other users' lists over their source: one read for every screen, held in memory for a few users (section 7.1).
class UserListKeeper(
    private val source: RemoteUserLists,
    private val scope: CoroutineScope,
    // Hears of what nobody expected, the source's exceptions, once the read that met one has ended.
    private val unexpected: (Throwable) -> Unit,
    // How many users' lists are held at most, beyond those a screen waits for.
    private val held: Int = USERS_HELD,
) : UserLists {
    // A read under way; it ends with no outcome when its user was forgotten.
    private class Read(
        val end: CompletableDeferred<Outcome<Unit>?> = CompletableDeferred(),
    ) {
        lateinit var job: Job
    }

    private class User(
        val name: String,
    ) {
        // None once the user was forgotten.
        val state = MutableStateFlow<UserListState?>(UserListState.Reading(emptyList(), null, null))

        // The screens that observe it and the calls that wait for its read.
        val waiting = MutableStateFlow(0)
        var read: Read? = null
        var shown = 0L
    }

    // Held for every change to the users and to a user's fields, never while the source is asked.
    private val lock = Any()
    private val users = mutableListOf<User>()
    private var shows = 0L

    init {
        require(held > 0)
        check(scope.isActive) { STOPPED }
    }

    override fun observeList(name: String): Flow<UserListState> =
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

    override suspend fun refreshList(name: String): Outcome<Unit> {
        while (true) {
            val (user, read) = synchronized(lock) { waitFor(name).let { it to (it.read ?: read(it)) } }
            read.job.start()
            try {
                read.end.await()?.let { return it }
            } finally {
                leave(user)
            }
        }
    }

    override fun sameUser(
        a: String,
        b: String,
    ): Boolean = source.sameUser(a, b)

    // A sign-out: nothing of anyone's list stays, and a screen still waiting for one has it read anew.
    fun forget() {
        synchronized(lock) { users.toList().forEach(::drop) }
    }

    // Beside the signed-in user's profile, what the screens read.
    fun beside(own: OwnProfile): UserRepository = object : UserRepository, OwnProfile by own, UserLists by this@UserListKeeper {}

    private fun waitFor(name: String): User {
        check(scope.isActive) { STOPPED }
        val user = users.firstOrNull { source.sameUser(it.name, name) } ?: User(name).also { users += it }
        user.shown = ++shows
        user.waiting.update { it + 1 }
        trim()
        return user
    }

    private fun leave(user: User) {
        synchronized(lock) {
            user.waiting.update { it - 1 }
            user.shown = ++shows
            trim()
        }
    }

    // Past the bound the user shown longest ago is forgotten, never one a screen waits for.
    private fun trim() {
        while (users.size > held) drop(users.filter { it.waiting.value == 0 }.minByOrNull { it.shown } ?: return)
    }

    private fun drop(user: User) {
        users -= user
        user.read?.let {
            it.job.cancel()
            it.end.complete(null)
        }
        user.read = null
        user.state.value = null
    }

    // The list held stays while it is read again; else the read alone shows. Started by its caller, outside the lock.
    private fun read(user: User): Read {
        val read = Read()
        user.read = read
        user.state.update { before ->
            when (before) {
                is UserListState.Whole -> before.copy(again = UserListProgress(0, null))
                else -> UserListState.Reading(emptyList(), null, before?.user)
            }
        }
        read.job = scope.launch(start = CoroutineStart.LAZY) { run(user, read) }
        read.job.invokeOnCompletion { read.end.complete(null) }
        return read
    }

    // One part at a time, and none while no screen waits; a title the source gives twice is on the list once.
    private suspend fun run(
        user: User,
        read: Read,
    ) {
        var entries = emptyList<ListEntry>()
        var total: Int? = null
        var described: UserProfile? = null
        var thrown: Exception? = null
        val titles = HashSet<AnimeId>()
        user.waiting.first { it > 0 }
        val end =
            try {
                source.read(user.name) { part ->
                    val new = part.entries.filter { titles.add(it.anime.id) }
                    if (new.isNotEmpty()) entries = entries + new
                    total = part.total ?: total
                    described = part.user ?: described
                    user.during(read) { before ->
                        when (before) {
                            is UserListState.Whole -> before.copy(again = UserListProgress(entries.size, total))
                            else -> UserListState.Reading(entries, total, described ?: before.user)
                        }
                    }
                    user.waiting.first { it > 0 }
                }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                Outcome.Failure(if (e is IOException) DataError.NoConnection else DataError.ServerError.also { thrown = e })
            }
        val error = (end as? Outcome.Failure)?.error
        // A whole list and the user's own answer replace what was held; any other failure leaves a list that was held.
        synchronized(lock) {
            user.during(read) { before ->
                when {
                    error == null -> UserListState.Whole(entries, described ?: before.user)
                    error == DataError.UnknownUser -> UserListState.None(error)
                    before is UserListState.Whole && !error.ofUser -> before.copy(again = null)
                    else -> UserListState.None(error, described ?: before.user)
                }
            } ?: return
            user.read = null
        }
        read.end.complete(end)
        thrown?.let(unexpected)
    }

    // Changes the state for the read that is the user's own; none for one whose user was forgotten.
    private fun User.during(
        read: Read,
        change: (UserListState) -> UserListState,
    ): UserListState? =
        synchronized(lock) {
            val before = state.value?.takeIf { this.read === read } ?: return null
            change(before).also { state.value = it }
        }

    // A list that was never read, or whose read failed, is read when a screen comes to wait for it.
    private val UserListState?.owed: Boolean
        get() = this is UserListState.Reading || (this is UserListState.None && !error.ofUser)

    companion object {
        const val USERS_HELD = 3
        private const val STOPPED = "The scope of other users' lists has ended"
    }
}

package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.data.manga.MangaMutationClaim
import io.github.kauri_off.kiroku.data.manga.MangaMutationExchange
import io.github.kauri_off.kiroku.data.manga.MangaMutationMethod
import io.github.kauri_off.kiroku.data.manga.MangaMutationObserver
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.fileRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

const val MANGA_CAPTURE_MINUTES = 10

data class MangaCaptureTarget(
    val id: MangaId,
    val method: MangaMutationMethod,
)

sealed interface MangaCaptureState {
    data object Ready : MangaCaptureState

    data class Armed(
        val target: MangaCaptureTarget,
    ) : MangaCaptureState

    data class Capturing(
        val target: MangaCaptureTarget,
    ) : MangaCaptureState

    data class Complete(
        val target: MangaCaptureTarget,
        val folder: File,
    ) : MangaCaptureState

    data class Stopped(
        val reason: MangaCaptureStop,
    ) : MangaCaptureState
}

enum class MangaCaptureStop { Unavailable, MissingEntry, Ended, Unconfirmed, Storage }

class MangaCaptureRecorder internal constructor(
    private val session: Lazy<MalSession>,
    private val scope: CoroutineScope,
    directory: () -> File,
    private val write: (File, String) -> Unit,
    private val time: TimeSource = TimeSource.Monotonic,
    private val erase: (File) -> Boolean = File::deleteRecursively,
) : MangaMutationObserver {
    private val root by lazy(directory)
    private val shown = MutableStateFlow<MangaCaptureState>(MangaCaptureState.Ready)
    val state = shown.asStateFlow()
    private val lock = Any()
    private val disk = Any()
    private var generation = 0L
    private var retrying: Long? = null
    private var cleanupPending = true
    private var watching = false
    private var resident: SessionOwner? = null
    private var reservation: Reservation? = null
    private var expiry: Job? = null
    private val lifetime = MANGA_CAPTURE_MINUTES.minutes

    private class Reservation(
        val owner: SessionOwner,
        val target: MangaCaptureTarget,
        val deadline: TimeMark,
        var claimed: Boolean = false,
    )

    internal val active: Boolean get() = synchronized(lock) { reservation != null }

    internal fun initialize(): Boolean {
        val epoch = synchronized(lock) { generation }
        if (retireFiles(epoch)) return true
        retryRetirement(epoch)
        return false
    }

    private fun retireFiles(epoch: Long): Boolean =
        synchronized(disk) {
            if (synchronized(lock) { generation != epoch || !cleanupPending }) return true
            val erased = storage { erase(root) } == true
            synchronized(lock) {
                if (generation != epoch) return true
                if (erased) {
                    cleanupPending = false
                } else {
                    stop(MangaCaptureStop.Storage)
                }
            }
            erased
        }

    private fun retryRetirement(epoch: Long) {
        synchronized(lock) {
            if (generation != epoch || !cleanupPending || retrying == epoch) return
            retrying = epoch
        }
        scope.launch {
            var attempt = 0
            do {
                delay(fileRetry(attempt++).toKotlinDuration())
            } while (!retireFiles(epoch))
        }
    }

    internal suspend fun arm(
        owner: SessionOwner?,
        id: MangaId,
        method: MangaMutationMethod,
        contains: suspend (SignInId, MangaId) -> Boolean,
    ) {
        val signIn = owner?.id
        if (signIn == null || id.value <= 0) {
            shown.value = MangaCaptureState.Stopped(MangaCaptureStop.Unavailable)
            return
        }
        val listed = contains(signIn, id)
        if (!initialize()) return
        val armed =
            session.value.withOwner(owner) {
                synchronized(lock) {
                    when {
                        reservation != null -> Unit
                        !session.value.mayWriteAs(signIn) -> stop(MangaCaptureStop.Ended)
                        !listed -> stop(MangaCaptureStop.MissingEntry)
                        cleanupPending -> stop(MangaCaptureStop.Storage)
                        resident != null && resident !== owner -> retryRetirement(retire())
                        else -> reserve(owner, MangaCaptureTarget(id, method))
                    }
                }
            }
        if (armed == null) synchronized(lock) { if (reservation == null) stop(MangaCaptureStop.Ended) }
    }

    private fun reserve(
        owner: SessionOwner,
        target: MangaCaptureTarget,
    ) {
        resident = owner
        val next = Reservation(owner, target, time.markNow() + lifetime)
        reservation = next
        shown.value = MangaCaptureState.Armed(target)
        expiry =
            scope.launch {
                delay(lifetime)
                synchronized(lock) { if (reservation === next) stop(MangaCaptureStop.Ended) }
            }
        if (!watching) {
            watching = true
            scope.launch {
                session.value.observeSession().collect {
                    synchronized(lock) {
                        val stored = resident ?: return@synchronized
                        if (!session.value.isCurrentOwner(stored)) {
                            retryRetirement(retire())
                        } else if (reservation != null && stored.id?.let(session.value::mayWriteAs) != true) {
                            stop(MangaCaptureStop.Ended)
                        }
                    }
                }
            }
        }
    }

    fun disarm(target: MangaCaptureTarget) {
        synchronized(lock) {
            if (reservation?.target === target) stop(MangaCaptureStop.Ended)
        }
    }

    fun forget() {
        synchronized(lock) { retire() }
        initialize()
    }

    private fun retire(): Long {
        resident = null
        cleanupPending = true
        stop(MangaCaptureStop.Ended)
        return ++generation
    }

    override fun candidate(
        owner: SignInId,
        id: MangaId,
        method: MangaMutationMethod,
    ): MangaMutationClaim? =
        synchronized(lock) {
            val run = reservation ?: return null
            if (!current(run)) {
                stop(MangaCaptureStop.Ended)
                return null
            }
            if (run.claimed || run.owner.id != owner || run.target != MangaCaptureTarget(id, method)) return null
            MangaMutationClaim { claim(run) }
        }

    private fun claim(run: Reservation): MangaMutationExchange? =
        synchronized(lock) {
            if (!current(run) || run.claimed) return null
            run.claimed = true
            shown.value = MangaCaptureState.Capturing(run.target)
            ObservedMangaMutation(run.target.id, run.target.method) { evidence -> finish(run, evidence) }
        }

    private fun current(run: Reservation): Boolean =
        reservation === run &&
            !run.deadline.hasPassedNow() &&
            session.value.isCurrentOwner(run.owner) &&
            run.owner.id?.let(session.value::mayWriteAs) == true

    private suspend fun finish(
        run: Reservation,
        evidence: MangaMutationEvidence?,
    ) {
        var pending: File? = null
        try {
            if (evidence == null) return
            withContext(Dispatchers.IO) {
                val caller = currentCoroutineContext()
                caller.ensureActive()
                pending =
                    synchronized(disk) {
                        if (!synchronized(lock) { current(run) }) return@withContext
                        storage {
                            val parent = Files.createDirectories(root.toPath())
                            Files.createDirectory(parent.resolve("run-${UUID.randomUUID()}")).toFile()
                        }
                    }
                val directory = pending
                val staged = directory != null && storage { stage(directory, evidence, run.target.method) } == true
                session.value.withOwner(run.owner) {
                    synchronized(lock) {
                        if (current(run)) {
                            caller.ensureActive()
                            val folder =
                                directory?.takeIf { staged }?.let {
                                    storage {
                                        val completed = File(it.parentFile, it.name.replaceFirst("run-", "capture-"))
                                        Files.move(it.toPath(), completed.toPath(), StandardCopyOption.ATOMIC_MOVE).toFile()
                                    }
                                }
                            if (folder == null) {
                                stop(MangaCaptureStop.Storage)
                            } else {
                                clear()
                                shown.value = MangaCaptureState.Complete(run.target, folder)
                            }
                        }
                    }
                }
            }
        } finally {
            val active = currentCoroutineContext().isActive
            synchronized(lock) {
                if (reservation === run) stop(if (active && current(run)) MangaCaptureStop.Unconfirmed else MangaCaptureStop.Ended)
            }
            pending?.let { directory ->
                withContext(Dispatchers.IO + NonCancellable) {
                    if (storage { directory.deleteRecursively() } != true) {
                        scope.launch(Dispatchers.IO) {
                            var attempt = 0
                            do {
                                delay(fileRetry(attempt++).toKotlinDuration())
                            } while (storage { directory.deleteRecursively() } != true)
                        }
                    }
                }
            }
        }
    }

    private fun stage(
        pending: File,
        evidence: MangaMutationEvidence,
        method: MangaMutationMethod,
    ): Boolean {
        val redaction = Sanitiser(emptyMap())
        val body =
            buildJsonObject {
                put("pages", evidence.pages)
                put("complete", true)
                put("present", evidence.readBack != null)
                evidence.readBack?.let { put("list_status", it) }
            }
        val writeReply = redaction.privateAnswer("manga_mutation_${method.name.lowercase()}", evidence.status, evidence.write)
        storePrivateAnswer(pending, writeReply, write)
        storePrivateAnswer(pending, redaction.privateAnswer("manga_mutation_read_back", 200, body), write)
        return true
    }

    private inline fun <T> storage(operation: () -> T): T? =
        try {
            operation()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun stop(reason: MangaCaptureStop) {
        clear()
        shown.value = MangaCaptureState.Stopped(reason)
    }

    private fun clear() {
        expiry?.cancel()
        expiry = null
        reservation = null
    }
}

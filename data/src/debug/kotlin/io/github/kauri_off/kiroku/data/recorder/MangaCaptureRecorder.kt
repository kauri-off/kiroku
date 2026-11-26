package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.data.manga.MangaMutationClaim
import io.github.kauri_off.kiroku.data.manga.MangaMutationEvidence
import io.github.kauri_off.kiroku.data.manga.MangaMutationMethod
import io.github.kauri_off.kiroku.data.manga.MangaMutationObserver
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
) : MangaMutationObserver {
    private val root by lazy(directory)
    private val shown = MutableStateFlow<MangaCaptureState>(MangaCaptureState.Ready)
    val state = shown.asStateFlow()
    private val lock = Any()
    private var initialized = false
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

    internal fun initialize(): Boolean =
        synchronized(lock) {
            if (!initialized) initialized = erase()
            initialized
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
        val armed =
            session.value.withOwner(owner) {
                synchronized(lock) {
                    when {
                        reservation != null -> Unit
                        !session.value.mayWriteAs(signIn) -> stop(MangaCaptureStop.Ended)
                        !listed -> stop(MangaCaptureStop.MissingEntry)
                        !initialize() -> stop(MangaCaptureStop.Storage)
                        resident != null && resident !== owner && !erase() -> stop(MangaCaptureStop.Storage)
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
                            if (erase()) resident = null
                            stop(MangaCaptureStop.Ended)
                        } else if (reservation != null && stored.id?.let(session.value::mayWriteAs) != true) {
                            stop(MangaCaptureStop.Ended)
                        }
                    }
                }
            }
        }
    }

    fun disarm(): Job =
        scope.launch {
            synchronized(lock) {
                if (reservation != null) stop(MangaCaptureStop.Ended)
            }
        }

    fun forget() {
        synchronized(lock) {
            if (erase()) resident = null
            stop(MangaCaptureStop.Ended)
        }
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

    private fun claim(run: Reservation): (suspend (MangaMutationEvidence?) -> Unit)? =
        synchronized(lock) {
            if (!current(run) || run.claimed) return null
            run.claimed = true
            val finish: suspend (MangaMutationEvidence?) -> Unit = { evidence -> finish(run, evidence) }
            shown.value = MangaCaptureState.Capturing(run.target)
            finish
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
        try {
            if (evidence == null) return
            val caller = currentCoroutineContext()
            session.value.withOwner(run.owner) {
                synchronized(lock) {
                    if (current(run)) {
                        val folder =
                            storage {
                                publish(evidence, run.target.method) {
                                    caller.ensureActive()
                                    current(run)
                                }
                            }
                        if (folder == null) {
                            stop(if (current(run)) MangaCaptureStop.Storage else MangaCaptureStop.Ended)
                        } else {
                            clear()
                            shown.value = MangaCaptureState.Complete(run.target, folder)
                        }
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (reservation === run) stop(if (current(run)) MangaCaptureStop.Unconfirmed else MangaCaptureStop.Ended)
            }
        }
    }

    private fun publish(
        evidence: MangaMutationEvidence,
        method: MangaMutationMethod,
        mayPublish: () -> Boolean,
    ): File? {
        val parent = Files.createDirectories(root.toPath())
        val id = UUID.randomUUID()
        val pending = Files.createDirectory(parent.resolve("run-$id")).toFile()
        try {
            val redaction = Sanitiser(emptyMap())
            val body =
                buildJsonObject {
                    put("pages", evidence.pages)
                    put("complete", true)
                    put("present", evidence.readBack != null)
                    evidence.readBack?.let { put("list_status", it) }
                }
            val writeReply = redaction.privateAnswer(method.name.lowercase(), evidence.status, evidence.write)
            storePrivateAnswer(pending, writeReply, write)
            storePrivateAnswer(pending, redaction.privateAnswer("read_back", 200, body), write)
            if (!mayPublish()) return null
            return Files.move(pending.toPath(), parent.resolve("capture-$id"), StandardCopyOption.ATOMIC_MOVE).toFile()
        } finally {
            pending.deleteRecursively()
        }
    }

    private fun erase(): Boolean = storage { root.deleteRecursively() } == true

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

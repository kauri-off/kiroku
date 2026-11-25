package io.github.kauri_off.kiroku.wiring

import android.content.Context
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.notify.NotificationStore
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.ListWrite
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.score.KeptScoreDistributions
import io.github.kauri_off.kiroku.data.anilist.AniListAiring
import io.github.kauri_off.kiroku.data.anime.MalSearch
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.manga.MalMangaRepository
import io.github.kauri_off.kiroku.data.network.AndroidDevice
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import io.github.kauri_off.kiroku.data.profile.MalOwnProfile
import io.github.kauri_off.kiroku.data.session.BackgroundRun
import io.github.kauri_off.kiroku.data.session.HeldBack
import io.github.kauri_off.kiroku.data.session.MalBackground
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.tenrai.TenraiScores
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.notify.MarkWatchedWork
import io.github.kauri_off.kiroku.signin.SignInOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
import okio.FileSystem
import java.io.File
import java.time.Clock
import kotlin.time.TimeSource

// One source of live data: its hosts, the name that each of its files carries, and what answers in MAL's place, if anything.
internal class LiveSource(
    val name: String,
    val hosts: MalHosts,
    val standIn: Interceptor? = null,
    // Where AniList is asked, when not at its own address.
    val aniList: String? = null,
    // What AniList's requests are spaced by.
    val aniListTime: TimeSource.WithComparableMarks = DeviceTime,
    // Where Tenrai is asked for score distributions, when not at its own address.
    val tenrai: String? = null,
) {
    companion object {
        val Account = LiveSource(notShown("account"), MalHosts.Real)
    }
}

// What the areas of a live graph share. Making it builds nothing: the HTTP client is built on its first use.
internal class LiveParts(
    val context: Context,
    val source: LiveSource,
    clientId: String,
    val clock: Clock,
    val scope: CoroutineScope,
    val unexpected: (Throwable) -> Unit,
    log: ((String) -> Unit)? = null,
    // The requests' judgement of the connection and the screens' are this one device's.
    val device: Device = AndroidDevice(context),
    // Emits when the device's clock was set, which airing then reads again.
    val clockSet: Flow<Unit> = deviceClockSet(context),
) {
    // The only HTTP client of the source.
    val http =
        MalHttp(
            source.hosts,
            clientId.trim(),
            BuildConfig.VERSION_NAME,
            device,
            clock,
            { file(BACK_OFF) },
            unexpected,
            log,
            standIn = source.standIn,
        )

    // The only AniList client, one per process as its graph is: two would each keep under AniList's limit and together exceed it.
    val aniList by lazy {
        val block = file(ANILIST_BLOCK)
        source.aniList?.let { AniListAiring(http, clock, block, it, source.aniListTime) }
            ?: AniListAiring(http, clock, block, time = source.aniListTime)
    }

    // The only Tenrai client, one per process as its graph is, with every answer kept for the process.
    val scores by lazy {
        val block = file(TENRAI_BLOCK)
        KeptScoreDistributions(
            source.tenrai?.let { TenraiScores(http, clock, block, it, DeviceTime) } ?: TenraiScores(http, clock, block, time = DeviceTime),
        )
    }

    // A file of this source alone, named after it: tokens open only under the name they were sealed with.
    fun file(kind: String): File = File(context.noBackupFilesDir, notShown("${source.name}.$kind"))

    // The source's own database, named after it; built on its first use, which is off the main thread.
    val database by lazy { KirokuDatabase.open(context, notShown("${source.name}.db")) }

    private companion object {
        val ANILIST_BLOCK = notShown("anilist.block")
        val TENRAI_BLOCK = notShown("tenrai.block")
        val BACK_OFF = notShown("backoff")
    }
}

// The app over one live source. Each area is in its own file and is built when it is first asked for.
internal class LiveGraph(
    parts: LiveParts,
    listStore: (LiveParts) -> LocalList = ::liveListStore,
    ownProfile: (LiveParts, MalSession) -> MalOwnProfile = ::ownProfileOf,
    notificationStore: (LiveParts, StoredPreferences) -> NotificationStore = ::liveNotificationStore,
    imageFiles: FileSystem = FileSystem.SYSTEM,
    airingOf: AiringOf = ::listAiring,
) : AppGraph,
    SignInOwner {
    override val clock: Clock = parts.clock
    private val built = lazy { liveSession(parts) }
    private val account by built
    override val images = parts.http.callFactory
    private val imageCache =
        LiveImages(parts.context, parts.source.name, { parts.file(notShown("retired-images")) }, images, parts.scope, imageFiles) { owner ->
            account.currentSession()?.let { it.owner == owner } == true
        }
    private val sessionWithCleanup by lazy {
        SignOutForgets(
            account,
            imageCache,
            { MarkWatchedWork.forget(parts.context, parts.source.name) },
            { list },
            { airing },
            { user },
            { notifications },
            runLog,
        )
    }
    override val session: SessionRepository get() = sessionWithCleanup

    override suspend fun signOut(expected: Session) {
        expected.owner?.let { sessionWithCleanup.signOut(it) }
    }

    override val list by lazy { liveList(parts, account, listStore) { preferences.forgetUsersChoices() } }
    override val anime by lazy { liveTitles(parts, preferences = { preferences }) }
    override val manga by lazy {
        val database = lazy { parts.database }
        MalMangaRepository(parts.http, { database.onFirstUse(parts.scope) }, parts.clock, account) {
            list.observeSyncedAt().first()
        }
    }
    override val airing by lazy { LiveAiring(parts, account.observeSession(), { list }, account::whileOwner, airingOf) }
    override val scores by lazy { parts.scores }
    override val search by lazy { MalSearch(parts.http, parts.clock) }
    override val user: LiveUser by lazy { liveUser(parts, account, ownProfile) }
    override val connection by lazy { liveConnection(parts) }
    override val preferences by lazy { livePreferences(parts) }
    override val signIn by lazy { liveSignIn(parts, account) }

    // MAL's one way in for work without a screen; it holds nothing of its own.
    private val wayIn by lazy { MalBackground(parts.http, account) }

    // What debug and preview builds keep of the notification engine's runs, for the debug menu; a release build keeps none.
    val runLog = liveRunLog(parts)

    // The source's notifications on the device; making it posts and reads nothing.
    private val shown by lazy { EpisodeNotifications(parts.context, parts.source.name, { titleLanguage.first() }) }
    override val notifications by lazy {
        LiveNotifications(
            parts,
            account.observeSession(),
            { list },
            { airing },
            { connection },
            { shown },
            { notificationStore(parts, preferences) },
            renew = { wayIn.run { } },
            reports = runLog,
        )
    }

    // Work without a screen sends nothing while the device is offline, so a run then reads only what is stored.
    private val device = parts.device
    private val unexpected = parts.unexpected

    // A device that cannot say is offline, which a caller waits out, never a failure of the work itself.
    private fun online(): Boolean =
        try {
            device.isOnline()
        } catch (e: Exception) {
            unexpected(e)
            false
        }

    override val withoutScreen: WithoutScreen =
        object : WithoutScreen {
            override val notificationRuns: NotificationRuns get() = notifications

            override suspend fun syncList() = inBackground { list.sync() }

            override suspend fun sendToList(write: ListWrite) = inBackground { list.send(write) }

            // AniList's own pace and refusal hold it back, never MAL's session or back-off.
            override suspend fun updateAiring(): Outcome<Unit> =
                if (online()) airing.refreshStale() else Outcome.Failure(DataError.NoConnection)

            override suspend fun titleOnList(id: AnimeId) = list.observeEntry(id).first()?.anime

            override suspend fun session() = account.observeSession().first()

            override val notSaved get() = shown
        }

    // The work behind MAL's way in, with what held it back told by its role; offline the tokens are not renewed either.
    private suspend fun inBackground(work: suspend () -> Outcome<Unit>): ListWork<Outcome<Unit>> {
        if (!online()) return ListWork.Done(Outcome.Failure(DataError.NoConnection))
        return when (val run = wayIn.run(work)) {
            is BackgroundRun.Ran -> ListWork.Done(run.value)
            HeldBack.SignedOut -> ListWork.SignedOut
            HeldBack.Expired -> ListWork.SessionExpired
            HeldBack.TokensUnread -> ListWork.SessionUnread
            is HeldBack.Refused -> ListWork.Refused(run.remaining)
        }
    }

    override fun newImageLoader(context: Context) =
        imageLoaderBuilder(context, images, standInPosters).memoryCache(null).diskCache(null).build()

    override fun imageLoaderFor(session: Session) = imageCache.loader(session.owner)

    override fun imagesRetired(session: Session) = imageCache.wasRetired(session.owner)

    // What debug and preview builds record MAL's answers with; it looks at once for a title an earlier run left on the list.
    val recorder = liveRecorder(parts, built)

    // For whatever is about to end the process: a session nobody asked for has nothing to write.
    suspend fun writeOwed() {
        if (built.isInitialized()) account.writeOwed()
    }
}

private class SignOutForgets(
    private val account: MalSession,
    private val images: LiveImages,
    // The waiting "Mark watched" taps, ended before the sign-out so that none is tried in a list that is not its own.
    private val taps: suspend () -> Unit,
    private val list: () -> ListSource,
    private val airing: () -> LiveAiring,
    private val user: () -> LiveUser,
    private val notifications: () -> LiveNotifications,
    private val runs: KeptRuns?,
) : SessionRepository by account {
    private val signingOut = mutableSetOf<SignInId?>()

    override suspend fun signOut() = signOut(account.currentSession()?.owner)

    suspend fun signOut(owner: SignInId?) {
        if (!synchronized(signingOut) { signingOut.add(owner) }) return
        try {
            if (account.currentSession()?.owner == owner) forget(owner)
        } finally {
            synchronized(signingOut) { signingOut.remove(owner) }
        }
    }

    private suspend fun forget(owner: SignInId?) {
        val signedOut =
            account.signOutIfCurrent(owner, onWriteFailure = { images.recordRetirement(owner) }) {
                cancelTaps()
                airing().retire(owner)
                user().forgetPublicLists()
                images.retire(owner)
            }
        if (!signedOut) return
        runs?.forget()
        notifications().forget()
        list().observeSyncedAt().first()
        user().forgetOwnProfile()
    }

    private suspend fun cancelTaps() {
        try {
            taps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A try left by a failed cancel finds nobody signed in, and ends sending and showing nothing.
            logTypes(e)
        }
    }
}

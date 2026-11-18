package io.github.kauri_off.kiroku.wiring

import android.content.Context
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.notify.NotificationRuns
import io.github.kauri_off.kiroku.core.notify.NotificationStore
import io.github.kauri_off.kiroku.core.repository.ListRepository
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.core.score.KeptScoreDistributions
import io.github.kauri_off.kiroku.data.anilist.AniListAiring
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.network.AndroidDevice
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import io.github.kauri_off.kiroku.data.profile.MalOwnProfile
import io.github.kauri_off.kiroku.data.session.BackgroundRun
import io.github.kauri_off.kiroku.data.session.MalBackground
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.tenrai.TenraiScores
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.signin.SignInOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.Interceptor
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

    // An area at its first use. One that cannot be built because the scope has ended is that end, to whoever asked for it.
    fun <T> built(area: Lazy<T>): T =
        try {
            area.value
        } catch (e: IllegalStateException) {
            scope.ensureActive()
            throw e
        }

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
    airingOf: AiringOf = ::listAiring,
) : AppGraph,
    SignInOwner {
    override val clock: Clock = parts.clock
    private val built = lazy { liveSession(parts) }
    private val account by built
    override val session: SessionRepository by lazy { SignOutForgets(account, { list }, { airing }, { user }, { notifications }) }
    override val list by lazy { liveList(parts, account, listStore) { preferences.forgetUsersChoices() } }
    override val anime by lazy { liveTitles(parts) }
    override val airing by lazy { LiveAiring(parts, account.observeSession(), { list }, airingOf) }
    override val scores by lazy { parts.scores }
    override val search by lazy { liveSearch(parts) }
    override val user: LiveUser by lazy { liveUser(parts, account, ownProfile) }
    override val connection by lazy { liveConnection(parts) }
    override val preferences by lazy { livePreferences(parts) }
    override val signIn by lazy { liveSignIn(parts, account) }

    // MAL's one way in for work without a screen; it holds nothing of its own.
    private val wayIn by lazy { MalBackground(parts.http, account) }
    override val notifications by lazy {
        LiveNotifications(
            parts,
            account.observeSession(),
            { list },
            { airing },
            { connection },
            { titleLanguage },
            { notificationStore(parts, preferences) },
            renew = { wayIn.run { } },
        )
    }
    override val notificationRuns: NotificationRuns get() = notifications

    override suspend fun <T> withoutScreen(work: suspend (ListRepository) -> T): BackgroundRun<T> = wayIn.run { work(list) }

    override val images = parts.http.callFactory

    // What debug and preview builds record MAL's answers with; it looks at once for a title an earlier run left on the list.
    val recorder = liveRecorder(parts, built)

    // For whatever is about to end the process: a session nobody asked for has nothing to write.
    suspend fun writeOwed() {
        if (built.isInitialized()) account.writeOwed()
    }
}

// S9: a sign-out takes the user's notifications, list, sorts, airing data and profiles with it, also when nothing has asked for them yet.
private class SignOutForgets(
    private val account: SessionRepository,
    private val list: () -> ListSource,
    private val airing: () -> LiveAiring,
    private val user: () -> LiveUser,
    private val notifications: () -> LiveNotifications,
) : SessionRepository by account {
    override suspend fun signOut() {
        account.signOut()
        notifications().forget()
        // A read of the list makes its store agree with the session; a store that fails it is cleared at its next use.
        list().observeSyncedAt().first()
        airing().forget()
        user().forget()
    }
}

package io.github.kauri_off.kiroku.wiring

import android.content.Context
import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.core.list.LocalList
import io.github.kauri_off.kiroku.core.repository.ListSource
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.network.AndroidDevice
import io.github.kauri_off.kiroku.data.network.Device
import io.github.kauri_off.kiroku.data.network.MalHosts
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.notShown
import io.github.kauri_off.kiroku.signin.SignInOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Clock

// One source of live data: its hosts, and the name that each of its files carries.
internal class LiveSource(
    val name: String,
    val hosts: MalHosts,
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
) {
    // The only HTTP client of the source.
    val http = MalHttp(source.hosts, clientId.trim(), BuildConfig.VERSION_NAME, device, clock, unexpected, log)

    // A file of this source alone, named after it: tokens open only under the name they were sealed with.
    fun file(kind: String): File = File(context.noBackupFilesDir, notShown("${source.name}.$kind"))

    // The source's own database, named after it; built on its first use, which is off the main thread.
    val database by lazy { KirokuDatabase.open(context, notShown("${source.name}.db")) }
}

// The app over one live source. Each area is in its own file and is built when it is first asked for.
internal class LiveGraph(
    parts: LiveParts,
    listStore: (LiveParts) -> LocalList = ::liveListStore,
) : AppGraph,
    SignInOwner {
    override val clock: Clock = parts.clock
    private val built = lazy { liveSession(parts) }
    private val account by built
    override val session: SessionRepository by lazy { SignOutForgets(account) { list } }
    override val list by lazy { liveList(parts, account, listStore) { preferences.clear() } }
    override val anime by lazy { liveTitles(parts) }
    override val airing by lazy { liveAiring(parts) }
    override val search by lazy { liveSearch(parts) }
    override val user by lazy { liveUser(parts, account) }
    override val connection by lazy { liveConnection(parts) }
    override val preferences by lazy { livePreferences(parts) }
    override val signIn by lazy { liveSignIn(parts, account) }
    override val images = parts.http.callFactory

    // What debug and preview builds record MAL's answers with; it looks at once for a title an earlier run left on the list.
    val recorder = liveRecorder(parts, built)

    // For whatever is about to end the process: a session nobody asked for has nothing to write.
    suspend fun writeOwed() {
        if (built.isInitialized()) account.writeOwed()
    }
}

// S9: a sign-out takes the user's list and choices with it at once, also when nothing has asked for the list yet.
private class SignOutForgets(
    private val account: SessionRepository,
    private val list: () -> ListSource,
) : SessionRepository by account {
    override suspend fun signOut() {
        account.signOut()
        // A read of the list makes its store agree with the session; a store that fails it is cleared at its next use.
        list().observeSyncedAt().first()
    }
}

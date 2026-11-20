package io.github.kauri_off.kiroku.notifysettings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import io.github.kauri_off.kiroku.LocalSplashHold
import io.github.kauri_off.kiroku.collectAsScreenState
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.notShown
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// Whether this install has had its one chance to ask; kept in a file, so a later start of any build sees it.
class AskedOnce(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, notShown("notification_permission_asked")))

    suspend fun done(): Boolean = withContext(io) { file.exists() }

    // Written also when the screen that asked is gone; a file that cannot be written means one more prompt at a later start.
    suspend fun mark() {
        withContext(NonCancellable + io) {
            try {
                file.createNewFile()
            } catch (_: IOException) {
            }
        }
    }
}

// Section 5.3: the system's prompt, once per install, as soon as someone is signed in and the window has drawn.
@Composable
fun AskForNotifications(
    session: SessionRepository,
    asked: AskedOnce = AskedOnce(LocalContext.current),
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val splash = LocalSplashHold.current
    // What the person answers shows on the notification settings, which read the device; nothing else depends on it.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Set with the launch and kept across a recreated window, which may come before the file is written.
    var launched by rememberSaveable { mutableStateOf(false) }
    val signedIn by remember(session) { session.observeSession().map { it != Session.SignedOut }.distinctUntilChanged() }
        .collectAsScreenState(initial = false)
    LaunchedEffect(signedIn) {
        if (!signedIn || launched || asked.done()) return@LaunchedEffect
        splash?.awaitDrawn()
        lifecycle.withResumed {
            if (!launched && !context.allowsNotifications()) {
                launched = true
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        asked.mark()
    }
}

private fun Context.allowsNotifications() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

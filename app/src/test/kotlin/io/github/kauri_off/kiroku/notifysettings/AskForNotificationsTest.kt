package io.github.kauri_off.kiroku.notifysettings

import android.Manifest
import android.app.NotificationManager
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityOptionsCompat
import io.github.kauri_off.kiroku.LocalSplashHold
import io.github.kauri_off.kiroku.SplashHold
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.repository.SessionRepository
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Duration.Companion.hours

// Section 5.3: the system's prompt, once per install, after a sign-in; never below Android 13.
@RunWith(RobolectricTestRunner::class)
class AskForNotificationsTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    @get:Rule
    val folder = TemporaryFolder()

    private val session = MutableStateFlow<Session>(Session.SignedIn(SignInId("a")))
    private val sessions =
        object : SessionRepository {
            override fun observeSession() = session

            override suspend fun signOut() {
                session.value = Session.SignedOut
            }
        }

    // What was handed to the system to ask, in order.
    private val asks = mutableListOf<Any?>()
    private val registry =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry =
                object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?,
                    ) {
                        asks += input
                    }
                }
        }

    private val marker by lazy { File(folder.root, "asked") }
    private var starts by mutableIntStateOf(0)

    // Each start of the app is a new composition over the same install's file.
    private fun app(hold: (View) -> SplashHold? = { null }) {
        compose.setContent {
            key(starts) {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides registry,
                    LocalSplashHold provides hold(LocalView.current),
                ) {
                    AskForNotifications(sessions, AskedOnce(marker, Dispatchers.Unconfined))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun laterStart() {
        starts++
        compose.waitForIdle()
    }

    private fun idle() = compose.waitForIdle()

    @Test
    fun `signed in, the system's prompt is asked for once, and no later start asks again`() {
        app()
        idle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asks)

        laterStart()
        idle()
        laterStart()
        idle()
        assertEquals(1, asks.size)
    }

    @Test
    fun `signed out nothing is asked, the sign-in asks, and a sign-out and another sign-in do not`() {
        session.value = Session.SignedOut
        app()
        idle()
        assertEquals(emptyList<Any?>(), asks)

        session.value = Session.SignedIn(SignInId("a"))
        idle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asks)

        session.value = Session.SignedOut
        idle()
        session.value = Session.SignedIn(SignInId("b"))
        idle()
        assertEquals(1, asks.size)
    }

    @Test
    fun `nothing is asked while the splash is held, and the prompt comes once the window has drawn`() {
        var splash: SplashHold? = null
        app { view -> splash ?: SplashHold(view, atMost = 1.hours).also { it.hold() }.also { splash = it } }
        idle()
        assertEquals(emptyList<Any?>(), asks)

        compose.runOnUiThread { splash!!.release() }
        idle()
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asks)
    }

    @Test
    fun `with the permission already given nothing is asked, also at a later start after it was taken away`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app()
        idle()
        assertEquals(emptyList<Any?>(), asks)

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        laterStart()
        idle()
        assertEquals(emptyList<Any?>(), asks)
    }

    @Test
    @Config(sdk = [30])
    fun `below Android 13 nothing is asked, also with the app's notifications turned off`() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        app()
        idle()
        laterStart()
        idle()
        assertEquals(emptyList<Any?>(), asks)
    }
}

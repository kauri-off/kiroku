package io.github.kauri_off.kiroku.notifysettings

import android.Manifest
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.link.LinkGraphApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// The app's own window asks at its start, signed in, and only the first time.
@RunWith(RobolectricTestRunner::class)
@Config(application = LinkGraphApplication::class, qualifiers = "en-w411dp-h891dp")
class AskForNotificationsAppTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun close() {
        scenario?.close()
    }

    // The permissions the window of a new start asked the system for, once the List is shown.
    private fun start(): List<String>? {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        compose.onNodeWithText("My list").assertExists()
        var asked: List<String>? = null
        scenario!!.onActivity { asked = shadowOf(it).lastRequestedPermission?.requestedPermissions?.toList() }
        return asked
    }

    @Test
    fun `the first start signed in asks the system for the permission, and a later start does not`() {
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), start())
        assertNull(start())
    }

    @Test
    @Config(sdk = [30])
    fun `below Android 13 no start asks, also with the app's notifications turned off`() {
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertNull(start())
        assertNull(start())
    }
}

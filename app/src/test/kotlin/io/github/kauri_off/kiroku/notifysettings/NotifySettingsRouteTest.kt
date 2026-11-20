package io.github.kauri_off.kiroku.notifysettings

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import io.github.kauri_off.kiroku.core.demo.Demo
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import io.github.kauri_off.kiroku.notify.EpisodeNotifications
import io.github.kauri_off.kiroku.shell.ShellMessages
import io.github.kauri_off.kiroku.wiring.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.TimeUnit

// The route over the demo, with the device's states as the system's own managers give them.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class NotifySettingsRouteTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose) { scope.cancel() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph = TestGraph(Demo(Fixtures.reference, scope))
    private val app = RuntimeEnvironment.getApplication()
    private val notifications = app.getSystemService(NotificationManager::class.java)

    private fun device(
        notificationsOn: Boolean,
        exactAlarms: Boolean,
        restricted: Boolean,
    ) {
        shadowOf(notifications).setNotificationsEnabled(notificationsOn)
        ShadowAlarmManager.setCanScheduleExactAlarms(exactAlarms)
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(restricted)
    }

    private fun show() {
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) {
                NotifySettingsRoute(graph, ShellMessages(scope, graph.list), SnackbarHostState(), {}, {}, {})
            }
        }
        compose.waitForIdle()
    }

    // The person goes to the system's screens and comes back.
    private fun leaveAndReturn() {
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    // The time of one check passes with the activity resumed.
    private fun passCheck() {
        shadowOf(Looper.getMainLooper()).idleFor(DEVICE_CHECK_EVERY.inWholeMilliseconds * 2, TimeUnit.MILLISECONDS)
        compose.waitForIdle()
    }

    private fun started(): Intent? = shadowOf(compose.activity).nextStartedActivity

    @Test
    fun `on a return from the system's screens the states are the device's at once`() {
        device(notificationsOn = false, exactAlarms = false, restricted = true)
        show()
        compose.onNodeWithText("Blocked in Android settings").assertExists()
        compose.onNodeWithText("Not allowed: notifications may come up to an hour late").assertExists()
        compose.onNodeWithText("Restricted in Android settings").assertExists()

        device(notificationsOn = true, exactAlarms = true, restricted = false)
        leaveAndReturn()
        compose.onNodeWithText("Blocked in Android settings").assertDoesNotExist()
        compose.onNodeWithText("Allowed").assertExists()
        compose.onNodeWithText("Allowed: notifications come on time").assertExists()
        compose.onNodeWithText("Not restricted").assertExists()
    }

    // The shade and a window beside the app change them while the screen stays resumed.
    @Test
    fun `the states follow the device while the screen stays resumed`() {
        device(notificationsOn = true, exactAlarms = true, restricted = false)
        show()
        compose.onNodeWithText("Allowed").assertExists()

        device(notificationsOn = false, exactAlarms = true, restricted = true)
        passCheck()
        assertEquals(Lifecycle.State.RESUMED, compose.activityRule.scenario.state)
        compose.onNodeWithText("Blocked in Android settings").assertExists()
        compose.onNodeWithText("Allowed").assertDoesNotExist()
        compose.onNodeWithText("Restricted in Android settings").assertExists()

        notifications.createNotificationChannel(NotificationChannel(EpisodeNotifications.CHANNEL, "x", NotificationManager.IMPORTANCE_NONE))
        device(notificationsOn = true, exactAlarms = true, restricted = false)
        passCheck()
        compose.onNodeWithText("The category “New episodes” is turned off").assertExists()
    }

    @Test
    fun `a system screen the device does not have gives way to the app's own info screen`() {
        device(notificationsOn = true, exactAlarms = false, restricted = false)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
        val settingsApp = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.android.settings" } }
        shadowOf(app.packageManager).addResolveInfoForIntent(details, settingsApp)
        shadowOf(app).checkActivities(true)
        show()
        compose.onNodeWithContentDescription("Not allowed", substring = true).performClick()
        val opened = started()!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals("package:${app.packageName}", opened.dataString)
    }

    @Test
    fun `no system screen opens without a tap, and each row opens its own`() {
        device(notificationsOn = false, exactAlarms = false, restricted = true)
        show()
        leaveAndReturn()
        leaveAndReturn()
        assertNull(started())

        compose.onNodeWithContentDescription("Blocked in Android settings", substring = true).performClick()
        val notificationScreen = started()!!
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notificationScreen.action)
        assertEquals(app.packageName, notificationScreen.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        compose.onNodeWithContentDescription("Not allowed", substring = true).performClick()
        val alarmScreen = started()!!
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, alarmScreen.action)
        assertEquals("package:${app.packageName}", alarmScreen.dataString)

        compose.onNodeWithContentDescription("Restricted", substring = true).performScrollTo().performClick()
        val battery = started()!!
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, battery.action)
        assertEquals("package:${app.packageName}", battery.dataString)
        assertNull(started())
    }

    @Test
    fun `with the channel turned off its row opens the channel's own screen`() {
        device(notificationsOn = true, exactAlarms = true, restricted = false)
        notifications.createNotificationChannel(NotificationChannel(EpisodeNotifications.CHANNEL, "x", NotificationManager.IMPORTANCE_NONE))
        show()
        compose.onNodeWithContentDescription("The category “New episodes” is turned off", substring = true).performClick()
        val screen = started()!!
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, screen.action)
        assertEquals(EpisodeNotifications.CHANNEL, screen.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun `the test notification arrives through the channel of new episodes and is not recorded as sent`() {
        device(notificationsOn = true, exactAlarms = true, restricted = false)
        show()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Send a test notification"))
        compose.onNodeWithText("Send a test notification").performClick()
        compose.waitUntil { notifications.activeNotifications.isNotEmpty() }
        val test = notifications.activeNotifications.single()
        assertEquals(EpisodeNotifications.CHANNEL, test.notification.channelId)
        assertEquals(EpisodeNotifications.testTag("test"), test.tag)
        assertEquals(emptyList<Any>(), runBlocking { graph.notifications.observeSent().first() })
    }
}

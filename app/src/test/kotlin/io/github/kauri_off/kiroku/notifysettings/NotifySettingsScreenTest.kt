package io.github.kauri_off.kiroku.notifysettings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.kauri_off.kiroku.design.composeRule
import io.github.kauri_off.kiroku.design.registered
import io.github.kauri_off.kiroku.design.theme.KirokuTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w411dp-h891dp-xhdpi")
class NotifySettingsScreenTest {
    private val compose = composeRule()

    @get:Rule
    val rule: TestRule = registered(compose)

    private val fixtures = NotifySettingsFixtureStates
    private val events = mutableListOf<NotifySettingsEvent>()

    private fun show(state: NotifySettingsState) =
        compose.setContent {
            KirokuTheme(dark = false, dynamicColor = false) { NotifySettingsScreen(state, { events += it }, SnackbarHostState()) }
        }

    @Test
    fun theSettingsAreChangedOnlyByTheirControls() {
        show(fixtures.state())
        compose.onNodeWithText("New episodes").assertIsOn().performClick()
        compose.onNodeWithText("Also for planned titles").assertIsOff().performClick()
        compose.onNodeWithText("1\u00A0hour later").performClick()
        compose.onNodeWithText("Right away").performClick()
        assertEquals(
            listOf(
                NotifySettingsEvent.SetOn(false),
                NotifySettingsEvent.SetAlsoPlanned(true),
                NotifySettingsEvent.SetDelay(NotifyDelay.Hour),
            ),
            events,
        )
    }

    @Test
    fun withNotificationsOffTheOtherSettingsCannotChangeAndNothingIsUpcoming() {
        show(fixtures.state(settings = fixtures.settings.copy(on = false)))
        compose.onNodeWithText("Also for planned titles").assertIsNotEnabled()
        compose.onNodeWithText("30\u00A0min later").assertIsNotEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Notifications are off"))
        compose.onNodeWithText(fixtures.upcoming.first().title).assertDoesNotExist()
    }

    @Test
    fun eachStateOfTheDeviceOpensItsSystemScreenOnlyOnATap() {
        show(fixtures.state(device = DeviceState(NotificationAccess.Blocked, ExactAlarms.NotAllowed, BatteryUse.Restricted)))
        assertEquals(emptyList<NotifySettingsEvent>(), events)
        compose.onNodeWithContentDescription("Blocked in Android settings", substring = true).performClick()
        compose.onNodeWithContentDescription("Not allowed: notifications may come late", substring = true).performClick()
        compose.onNodeWithContentDescription("Restricted: notifications may not come", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Send a test notification").performScrollTo().performClick()
        assertEquals(
            DeviceSetting.entries.map { NotifySettingsEvent.OpenSetting(it) } + NotifySettingsEvent.SendTest,
            events,
        )
    }

    @Test
    fun exactAlarmsWithoutASystemScreenOpenNothing() {
        show(fixtures.state(device = fixtures.allowed.copy(exactAlarms = ExactAlarms.NoSetting)))
        compose.onNodeWithText("Alarms and reminders").assertHasNoClickAction()
        compose.onNodeWithContentDescription("Not restricted", substring = true).assertHasClickAction()
    }

    @Test
    fun signedOutTheRecordIsReplacedBySignIn() {
        show(fixtures.state(record = null))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sign in"))
        compose.onNodeWithText("Sign in").performClick()
        compose.onNodeWithText("Recently sent").assertDoesNotExist()
        compose.onNodeWithText("Upcoming").assertDoesNotExist()
        assertEquals(listOf<NotifySettingsEvent>(NotifySettingsEvent.SignIn), events)
    }

    @Test
    fun aSentNotificationSaysWhenItAiredWhenItWasSentAndHowItWasChecked() {
        val sent = fixtures.sent.first { it.recheck == Recheck.Offline }
        show(fixtures.state())
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("not checked: offline", substring = true))
        compose.onNodeWithText("Sent Sat\u00A05:44", substring = true, useUnmergedTree = true).assertExists()
        compose
            .onNodeWithText(
                "Episode\u00A0${sent.episode} ·\u00A0aired Sat\u00A05:30",
                substring = true,
                useUnmergedTree = true,
            ).assertExists()
        compose.onNodeWithContentDescription("not checked: offline", substring = true).performClick()
        assertEquals(listOf<NotifySettingsEvent>(NotifySettingsEvent.OpenTitle(sent.id)), events)
    }
}

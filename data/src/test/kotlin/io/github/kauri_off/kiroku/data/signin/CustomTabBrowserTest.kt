package io.github.kauri_off.kiroku.data.signin

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

// S1: MAL's page is handed to the user's browser as a Custom Tab, by an intent and nothing else.
@RunWith(RobolectricTestRunner::class)
class CustomTabBrowserTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val address = "$AUTHORIZE?response_type=code&state=s"
    private val customTabSession = "android.support.customtabs.extra.SESSION"

    // A default browser that offers Custom Tabs.
    private fun install(browser: String) {
        val packages = shadowOf(application.packageManager)
        val view = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = browser } }
        packages.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse("http://")), view)
        val service = ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = browser } }
        packages.addResolveInfoForIntent(Intent("android.support.customtabs.action.CustomTabsService").setPackage(browser), service)
    }

    @Test
    fun thePageOpensAsACustomTabOfTheUsersBrowserInTheAppsTask() {
        install("the.browser")

        assertTrue(CustomTabBrowser(activity).open(address))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(address, started.dataString)
        assertTrue(started.hasExtra(customTabSession))
        assertEquals("the.browser", started.`package`)
        assertNull(started.component)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun withoutABrowserThatHasCustomTabsThePageGoesToWhateverOpensWebAddresses() {
        assertTrue(CustomTabBrowser(activity).open(address))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(address, started.dataString)
        assertNull(started.`package`)
        assertNull(started.component)
    }

    @Test
    fun fromOutsideAnActivityThePageOpensInATaskOfItsOwn() {
        assertTrue(CustomTabBrowser(application).open(address))

        val started = shadowOf(application).nextStartedActivity
        assertEquals(address, started.dataString)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun aDeviceWhereNothingOpensThePageSaysSo() {
        shadowOf(application).checkActivities(true)

        assertFalse(CustomTabBrowser(activity).open(address))
    }
}

package io.github.kauri_off.kiroku.signin

import io.github.kauri_off.kiroku.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

// The redirect the build names to MAL is the one its manifest takes; the build checks each build type's manifest.
@RunWith(RobolectricTestRunner::class)
class RedirectFilterTest {
    private val android = "http://schemas.android.com/apk/res/android"

    @Test
    fun `the redirect the sign-in names is the one the redirect activity takes`() {
        val manifest = RuntimeEnvironment.getApplication().assets.openXmlResourceParser("AndroidManifest.xml")
        val taken = mutableListOf<String>()
        var activity = ""
        while (manifest.next() != XmlPullParser.END_DOCUMENT) {
            if (manifest.eventType != XmlPullParser.START_TAG) continue
            if (manifest.name == "activity") activity = manifest.getAttributeValue(android, "name")
            if (manifest.name == "data" && activity.endsWith(".signin.RedirectActivity")) {
                taken += manifest.getAttributeValue(android, "scheme") + "://" + manifest.getAttributeValue(android, "host")
            }
        }
        assertEquals(listOf(BuildConfig.OAUTH_REDIRECT), taken)
    }
}

package io.github.kauri_off.kiroku

import android.content.pm.ApplicationInfo
import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LauncherIconTest {
    @Test
    fun iconAndRoundIconAreAdaptiveWithEveryLayer() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.applicationInfo
        val roundIcon = ApplicationInfo::class.java.getField("roundIconRes").getInt(info)
        for (id in listOf(info.icon, roundIcon)) {
            assertTrue(id != 0)
            val icon = app.getDrawable(id)
            assertTrue(icon is AdaptiveIconDrawable)
            icon as AdaptiveIconDrawable
            assertNotNull(icon.background)
            assertNotNull(icon.foreground)
            assertNotNull(icon.monochrome)
        }
    }
}

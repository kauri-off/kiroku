package io.github.kauri_off.kiroku.wiring

import android.content.ComponentName
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// What runs when the process starts, before any screen: a library that adds itself here fails this test.
@RunWith(RobolectricTestRunner::class)
class StartUpTest {
    @Test
    fun `nothing of the network is set up when the process starts`() {
        val app = RuntimeEnvironment.getApplication()
        val provider = ComponentName(app, "androidx.startup.InitializationProvider")
        val initializers = app.packageManager.getProviderInfo(provider, PackageManager.GET_META_DATA).metaData
        assertEquals(
            setOf(
                "androidx.emoji2.text.EmojiCompatInitializer",
                "androidx.lifecycle.ProcessLifecycleInitializer",
                "androidx.profileinstaller.ProfileInstallerInitializer",
            ),
            initializers.keySet(),
        )
    }
}

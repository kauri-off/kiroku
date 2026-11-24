package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.KirokuApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.experimental.LazyApplication

@RunWith(RobolectricTestRunner::class)
@LazyApplication(LazyApplication.LazyLoad.ON)
class ImageCacheUpgradeTest {
    @Test
    fun `application startup removes the legacy directory without opening a session or using Coil`() =
        runBlocking {
            val files = FileSystem.SYSTEM
            val legacy = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coil3_disk_cache"
            files.createDirectories(legacy)
            files.write(legacy / "orphan") { writeUtf8("fixture") }
            val app = RuntimeEnvironment.getApplication() as KirokuApplication
            try {
                withTimeout(5_000) {
                    while (files.exists(legacy)) delay(10)
                }
                assertFalse(files.exists(legacy))
            } finally {
                app.endWork()
            }
        }
}

package io.github.kauri_off.kiroku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

// S8: the app takes no part in cloud backup or device transfer, whatever it stores and wherever.
@RunWith(RobolectricTestRunner::class)
class BackupRuleTest {
    private val app = RuntimeEnvironment.getApplication()
    private val android = "http://schemas.android.com/apk/res/android"

    // Every place the platform backs up or transfers from.
    private val domains =
        setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )

    @Test
    fun backupIsOffAndTheRulesExcludeEveryDomainFromBackupAndTransfer() {
        val manifest = app.assets.openXmlResourceParser("AndroidManifest.xml")
        while (!(manifest.eventType == XmlPullParser.START_TAG && manifest.name == "application")) manifest.next()
        assertFalse(manifest.getAttributeBooleanValue(android, "allowBackup", true))
        val rules = manifest.getAttributeResourceValue(android, "dataExtractionRules", 0)
        assertTrue(rules != 0)

        val excluded = mutableMapOf<String, MutableSet<String>>()
        val parser = app.resources.getXml(rules)
        var section = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "data-extraction-rules" -> Unit
                "cloud-backup", "device-transfer" -> section = parser.name.also { excluded[it] = mutableSetOf() }
                "exclude" -> {
                    assertEquals(".", parser.getAttributeValue(null, "path"))
                    excluded.getValue(section) += parser.getAttributeValue(null, "domain")
                }
                else -> throw AssertionError("${parser.name} lets data out")
            }
        }
        assertEquals(mapOf("cloud-backup" to domains, "device-transfer" to domains), excluded)
    }
}

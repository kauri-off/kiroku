package io.github.kauri_off.kiroku.link

import android.content.Intent
import android.net.Uri
import io.github.kauri_off.kiroku.core.fixtures.AnimeLinks
import io.github.kauri_off.kiroku.rules.projectDir
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

// What the system gives the app of MAL's addresses, by the merged manifest; the build checks the filter's exact shape.
@RunWith(RobolectricTestRunner::class)
@Config(application = LinksApplication::class)
class LinkFilterTest {
    private val context = RuntimeEnvironment.getApplication()

    // The addresses a browser's link reaches the app with.
    private fun taken(addresses: Collection<String>): List<String> =
        addresses.filter { address ->
            val view =
                Intent(Intent.ACTION_VIEW, Uri.parse(address))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(context.packageName)
            context.packageManager.queryIntentActivities(view, 0).any { it.activityInfo.name == LinkActivity::class.java.name }
        }

    @Test
    fun `from Android 12 the app is given a title's page and nothing else of MAL's site`() {
        assertEquals(AnimeLinks.opened.keys.toList(), taken(AnimeLinks.opened.keys))
        assertEquals(emptyList<String>(), taken(AnimeLinks.refused))
        assertEquals(AnimeLinks.refusedOnArrival, taken(AnimeLinks.refusedOnArrival))
    }

    // The manifest enables the link's activity by this value alone, which the build checks.
    @Test
    fun `before Android 12 no address is claimed, since a filter cannot tell a title's page from its sub-pages`() {
        val claimed =
            File(projectDir, "app/src/main/res")
                .walk()
                .filter { it.name == "links.xml" }
                .associate {
                    it.parentFile.name to
                        Regex("""<bool name="links_claimed">(\w+)</bool>""").find(it.readText())?.groupValues?.get(1)
                }
        assertEquals(mapOf("values" to "false", "values-v31" to "true"), claimed)
    }
}

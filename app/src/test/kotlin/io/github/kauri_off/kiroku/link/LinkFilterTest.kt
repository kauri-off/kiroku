package io.github.kauri_off.kiroku.link

import android.content.Intent
import android.net.Uri
import io.github.kauri_off.kiroku.core.fixtures.AnimeLinks
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
        assertEquals(AnimeLinks.handedOn, taken(AnimeLinks.handedOn))
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 12 no address is claimed, since a filter cannot tell a title's page from its sub-pages`() {
        assertEquals(emptyList<String>(), taken(AnimeLinks.opened.keys + AnimeLinks.handedOn + AnimeLinks.refused))
    }
}

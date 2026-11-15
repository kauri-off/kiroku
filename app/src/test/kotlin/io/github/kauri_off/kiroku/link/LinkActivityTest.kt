package io.github.kauri_off.kiroku.link

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.github.kauri_off.kiroku.MainActivity
import io.github.kauri_off.kiroku.core.fixtures.AnimeLinks
import io.github.kauri_off.kiroku.core.model.AnimeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class LinksApplication :
    Application(),
    LinksOwner {
    override val links = AppLinks()
}

@RunWith(RobolectricTestRunner::class)
@Config(application = LinksApplication::class)
class LinkActivityTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val links get() = (application as LinksApplication).links

    private fun view(uri: String?) = Intent(Intent.ACTION_VIEW, uri?.let(Uri::parse), application, LinkActivity::class.java)

    private fun create(
        intent: Intent,
        saved: Bundle? = null,
    ) = assertTrue(
        Robolectric
            .buildActivity(LinkActivity::class.java, intent)
            .create(saved)
            .get()
            .isFinishing,
    )

    // The link activity is gone, and the app's own screen is brought up, in its own task, without the address.
    private fun arrive(
        intent: Intent,
        saved: Bundle? = null,
    ) {
        create(intent, saved)
        val started = shadowOf(application).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags, started.flags and flags)
        assertNull(started.data)
        assertNull(started.extras)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    // The link activity is gone, and the address went whole to the browser, not to this app.
    private fun handedOn(address: String) {
        create(view(address))
        val started = shadowOf(application).nextStartedActivity
        assertEquals(address, Intent.ACTION_VIEW, started.action)
        assertEquals(address, address, started.dataString)
        assertTrue(address, started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(address, started.component)
        val selector = checkNotNull(started.selector)
        assertEquals(Intent.ACTION_MAIN, selector.action)
        assertEquals(setOf(Intent.CATEGORY_APP_BROWSER), selector.categories)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNull(shadowOf(application).nextStartedActivity)
        assertNull(address, links.link.value)
    }

    @Test
    fun `a link to a title hands its id to the app once and brings the app's screen up`() {
        for ((address, id) in AnimeLinks.opened) {
            arrive(view(address))
            assertEquals(address, AnimeId(id), links.take()?.id)
            assertNull(links.take())
        }
    }

    @Test
    fun `whatever else the system hands the app goes on to the browser, and an intent without an address to nothing`() {
        for (address in AnimeLinks.handedOn + AnimeLinks.refused + AnimeLinks.unreadable) handedOn(address)
        create(view(null))
        assertNull(shadowOf(application).nextStartedActivity)
        assertNull(links.link.value)
    }

    @Test
    fun `an intent replayed from the recent tasks brings the app up without its link`() {
        arrive(view(AnimeLinks.opened.keys.first()).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertNull(links.link.value)
    }

    @Test
    fun `the activity made again for its link does not hand it over twice`() {
        arrive(view(AnimeLinks.opened.keys.first()), saved = Bundle())
        assertNull(links.link.value)
    }

    @Test
    fun `two links in a row are two links, and the app takes each once`() {
        val first = AnimeLinks.opened.keys.first()
        arrive(view(first))
        val one = links.link.value!!
        links.taken(one)
        arrive(view(first))
        val two = links.link.value!!
        assertEquals(one.id, two.id)
        assertTrue(one != two)
        links.taken(one)
        assertEquals(two, links.link.value)
    }
}

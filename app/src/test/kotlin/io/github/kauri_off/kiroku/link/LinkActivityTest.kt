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

class NoNotice : LinkNotice {
    override suspend fun due() = false

    override suspend fun shown() = Unit
}

class LinksApplication :
    Application(),
    LinksOwner {
    override val links = AppLinks(NoNotice())
}

@RunWith(RobolectricTestRunner::class)
@Config(application = LinksApplication::class)
class LinkActivityTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val links get() = (application as LinksApplication).links

    private fun view(uri: String?) = Intent(Intent.ACTION_VIEW, uri?.let(Uri::parse), application, LinkActivity::class.java)

    // The link activity is gone; with [comesBack], the app's own screen is brought up, in its own task, without the address.
    private fun arrive(
        intent: Intent,
        comesBack: Boolean = true,
        saved: Bundle? = null,
    ) {
        val activity = Robolectric.buildActivity(LinkActivity::class.java, intent).create(saved).get()
        assertTrue(activity.isFinishing)
        val started = shadowOf(application).nextStartedActivity
        if (!comesBack) {
            assertNull(started)
            return
        }
        assertEquals(MainActivity::class.java.name, started.component?.className)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(flags, started.flags and flags)
        assertNull(started.data)
        assertNull(started.extras)
        assertNull(shadowOf(application).nextStartedActivity)
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
    fun `an address that is not a title's page opens nothing of the app's`() {
        for (address in AnimeLinks.refused + AnimeLinks.refusedOnArrival + AnimeLinks.unreadable) {
            arrive(view(address), comesBack = false)
            assertNull(address, links.link.value)
        }
        arrive(view(null), comesBack = false)
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

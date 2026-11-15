package io.github.kauri_off.kiroku.link

import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment

// The app as someone who has seen the links' notice starts it: a test of something else meets no notice over the List.
class NoticeSeen : ExternalResource() {
    override fun before() = runBlocking { checkNotNull(RuntimeEnvironment.getApplication().appLinks).notice.shown() }
}

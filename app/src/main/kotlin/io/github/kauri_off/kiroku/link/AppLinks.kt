package io.github.kauri_off.kiroku.link

import android.content.Context
import io.github.kauri_off.kiroku.core.link.Link
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.util.concurrent.atomic.AtomicLong

// One link handed over; two links to one page are two of these.
data class OpenedLink(
    val link: Link,
    val serial: Long,
)

// The links to titles and profiles the system handed to the app, each taken once.
class AppLinks {
    private val serials = AtomicLong()
    private val pending = MutableStateFlow<OpenedLink?>(null)

    // The link not yet taken, the latest one when several arrived.
    val link: StateFlow<OpenedLink?> get() = pending

    fun opened(link: Link) {
        pending.value = OpenedLink(link, serials.incrementAndGet())
    }

    fun take(): OpenedLink? = pending.getAndUpdate { null }

    fun taken(link: OpenedLink) {
        pending.compareAndSet(link, null)
    }
}

// A process that takes links: the application.
interface LinksOwner {
    val links: AppLinks
}

val Context.appLinks: AppLinks? get() = (applicationContext as? LinksOwner)?.links

package io.github.kauri_off.kiroku.link

import android.content.Context
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import java.util.concurrent.atomic.AtomicLong

// One link handed over; two links to one title are two of these.
data class OpenedLink(
    val id: AnimeId,
    val serial: Long,
)

// The links to titles the system handed to the app, each taken once, and the notice that the system must allow them.
class AppLinks(
    val notice: LinkNotice,
) {
    private val serials = AtomicLong()
    private val pending = MutableStateFlow<OpenedLink?>(null)

    // The link not yet taken, the latest one when several arrived.
    val link: StateFlow<OpenedLink?> get() = pending

    fun opened(id: AnimeId) {
        pending.value = OpenedLink(id, serials.incrementAndGet())
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

// Whether the screen that says the system must allow the links is due, and that it was shown.
interface LinkNotice {
    // The system asks the person, the links do not open in the app, and the screen was never shown.
    suspend fun due(): Boolean

    suspend fun shown()
}

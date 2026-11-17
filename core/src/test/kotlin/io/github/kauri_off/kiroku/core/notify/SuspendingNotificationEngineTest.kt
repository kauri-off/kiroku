package io.github.kauri_off.kiroku.core.notify

import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import java.time.Instant

// The contract over a store that suspends before and after every call and every value, so that other work runs in between.
class SuspendingNotificationEngineTest : NotificationEngineContract() {
    override fun store(scope: TestScope): NotificationStore =
        object : NotificationStore {
            private val inner = InMemoryNotificationStore()

            override fun observeSettings(): Flow<NotificationSettings> = suspending(inner.observeSettings())

            override suspend fun writeSettings(settings: NotificationSettings) = suspending { inner.writeSettings(settings) }

            override suspend fun read(): StoredNotifications = suspending { inner.read() }

            override fun observeSent(): Flow<List<SentNotification>> = suspending(inner.observeSent())

            override suspend fun write(
                record: NotificationRecord,
                sent: List<SentNotification>,
                keptFrom: Instant,
            ) = suspending { inner.write(record, sent, keptFrom) }

            override suspend fun clear(owner: SignInId?) = suspending { inner.clear(owner) }

            private fun <T> suspending(stored: Flow<T>): Flow<T> =
                flow {
                    yield()
                    emitAll(stored.onEach { yield() })
                }

            private suspend fun <T> suspending(call: suspend () -> T): T {
                yield()
                return call().also { yield() }
            }
        }
}

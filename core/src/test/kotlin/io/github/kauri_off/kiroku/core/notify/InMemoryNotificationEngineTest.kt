package io.github.kauri_off.kiroku.core.notify

import kotlinx.coroutines.test.TestScope

class InMemoryNotificationEngineTest : NotificationEngineContract() {
    override fun store(scope: TestScope): NotificationStore = InMemoryNotificationStore()
}

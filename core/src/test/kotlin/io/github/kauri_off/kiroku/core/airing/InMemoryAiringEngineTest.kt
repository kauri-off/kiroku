package io.github.kauri_off.kiroku.core.airing

import kotlinx.coroutines.test.TestScope

class InMemoryAiringEngineTest : AiringEngineContract() {
    override fun store(scope: TestScope): AiringStore = InMemoryAiringStore()
}

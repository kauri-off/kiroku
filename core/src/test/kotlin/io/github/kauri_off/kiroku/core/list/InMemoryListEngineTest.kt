package io.github.kauri_off.kiroku.core.list

import kotlinx.coroutines.test.TestScope

class InMemoryListEngineTest : ListEngineContract() {
    override fun local(scope: TestScope): LocalList = InMemoryLocalList()
}

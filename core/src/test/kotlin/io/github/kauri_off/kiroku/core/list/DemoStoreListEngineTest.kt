package io.github.kauri_off.kiroku.core.list

import io.github.kauri_off.kiroku.core.demo.DemoStore
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.test.TestScope

class DemoStoreListEngineTest : ListEngineContract() {
    override fun local(scope: TestScope): LocalList = DemoStore(emptyList(), SignInId("demo"))
}

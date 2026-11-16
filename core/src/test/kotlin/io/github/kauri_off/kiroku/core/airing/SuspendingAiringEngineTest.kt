package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield

// The contract over a store whose every call suspends, as a database's does, so that other work runs in between.
class SuspendingAiringEngineTest : AiringEngineContract() {
    override fun store(scope: TestScope): AiringStore =
        object : AiringStore {
            private val inner = InMemoryAiringStore()

            override fun observe(): Flow<Map<AnimeId, StoredAiring>> =
                flow {
                    yield()
                    emitAll(inner.observe().onEach { yield() })
                }

            override suspend fun read(): Map<AnimeId, StoredAiring> {
                yield()
                return inner.read()
            }

            override suspend fun write(titles: Map<AnimeId, StoredAiring?>) {
                yield()
                inner.write(titles)
                yield()
            }
        }
}

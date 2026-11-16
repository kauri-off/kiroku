package io.github.kauri_off.kiroku.core.airing

import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// A store that lives as long as its process.
class InMemoryAiringStore : AiringStore {
    private val stored = MutableStateFlow(emptyMap<AnimeId, StoredAiring>())

    override fun observe(): Flow<Map<AnimeId, StoredAiring>> = stored

    override suspend fun read(): Map<AnimeId, StoredAiring> = stored.value

    override suspend fun write(titles: Map<AnimeId, StoredAiring?>) {
        stored.update { held ->
            val next = held.toMutableMap()
            for ((id, airing) in titles) if (airing == null) next.remove(id) else next[id] = airing
            next
        }
    }
}

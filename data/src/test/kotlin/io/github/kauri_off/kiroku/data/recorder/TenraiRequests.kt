package io.github.kauri_off.kiroku.data.recorder

// One request of the Tenrai recorder's list: a title's statistics by MAL id.
internal data class TenraiAsk(
    val name: String,
    val id: Long,
)

// What the build's Tenrai recorder asks, one case per line; each title but the unknown one is a title of the sandbox's.
internal val TenraiRequests: List<TenraiAsk> =
    listOf(
        TenraiAsk("popular", 1),
        TenraiAsk("airing", 61987),
        TenraiAsk("few_votes", 65189),
        TenraiAsk("not_yet_aired", 59068),
        TenraiAsk("unknown", 2),
    )

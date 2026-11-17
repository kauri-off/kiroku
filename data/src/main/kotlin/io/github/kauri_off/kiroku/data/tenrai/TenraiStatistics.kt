package io.github.kauri_off.kiroku.data.tenrai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

// What is asked of Tenrai, the same for the client and for the recorder whose answers its mapper is tested on.
internal object TenraiStatistics {
    const val ADDRESS = "https://api.tenrai.org/v1/"

    // A title's statistics by its MAL id, the votes per score among them.
    fun url(
        id: Long,
        address: HttpUrl = ADDRESS.toHttpUrl(),
    ): HttpUrl =
        address
            .newBuilder()
            .addPathSegment("anime")
            .addPathSegment(id.toString())
            .addPathSegment("statistics")
            .build()
}

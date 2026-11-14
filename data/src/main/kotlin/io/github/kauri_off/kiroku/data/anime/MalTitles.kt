package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.clientIdOutcome
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// A title's page from MAL, asked with the client id alone.
class MalTitles(
    private val http: MalHttp,
) {
    private val api by http.api(TitleApi::class)

    // [adult]: the title's adult recommendations too; its related titles and the title itself come either way.
    suspend fun title(
        id: AnimeId,
        adult: Boolean,
    ): Outcome<MalTitle> =
        http.call { api.title(id.value, MalFields.DETAILS, nsfw = if (adult) true else null).toTitle() }.clientIdOutcome()
}

private interface TitleApi {
    @GET("anime/{id}")
    suspend fun title(
        @Path("id") id: Long,
        @Query("fields") fields: String,
        @Query("nsfw") nsfw: Boolean?,
    ): JsonObject
}

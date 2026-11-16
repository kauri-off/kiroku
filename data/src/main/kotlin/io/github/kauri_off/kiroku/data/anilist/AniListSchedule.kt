package io.github.kauri_off.kiroku.data.anilist

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What is asked of AniList, the same for the client and for the recorder whose answers its mappers are tested on.
internal object AniListSchedule {
    const val ADDRESS = "https://graphql.anilist.co"

    // AniList answers no more titles than this at once, and no more of one title's schedule.
    const val TITLES_PER_PAGE = 50
    const val EPISODES_PER_PAGE = 25

    // Titles by MAL id: AniList's episode total and state, and the schedule's episodes with their times.
    const val QUERY =
        "query(\$ids:[Int],\$page:Int,\$schedulePage:Int){" +
            "Page(page:\$page,perPage:$TITLES_PER_PAGE){pageInfo{hasNextPage}" +
            "media(idMal_in:\$ids,type:ANIME){idMal episodes status " +
            "airingSchedule(page:\$schedulePage,perPage:$EPISODES_PER_PAGE){pageInfo{hasNextPage}nodes{episode airingAt}}}}}"

    // The body of a request for [ids]: their [page]-th page of titles and each title's [schedulePage]-th page of episodes.
    fun body(
        ids: List<Int>,
        page: Int = 1,
        schedulePage: Int = 1,
    ): String =
        buildJsonObject {
            put("query", QUERY)
            put(
                "variables",
                buildJsonObject {
                    put("ids", JsonArray(ids.map(::JsonPrimitive)))
                    put("page", page)
                    put("schedulePage", schedulePage)
                },
            )
        }.toString()
}

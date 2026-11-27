package io.github.kauri_off.kiroku.data.network

// The fields asked of MAL, the same for the clients and for the recorder whose answers their mappers are tested on.
object MalFields {
    // A title as a row holds it: on the list, in search and among a title's related titles and recommendations.
    const val SUMMARY =
        "id,title,main_picture,alternative_titles,media_type,status,num_episodes,start_date,broadcast,mean,rank,genres," +
            "average_episode_duration"

    const val SUGGESTIONS = "$SUMMARY,nsfw"

    internal const val PICTURES = "pictures"

    const val DETAILS =
        "$SUMMARY,$PICTURES,end_date,synopsis,start_season,source,studios,rating,statistics," +
            "related_anime{$SUMMARY},recommendations{$SUMMARY}"

    const val MANGA_SUMMARY = "id,title,alternative_titles,main_picture,media_type,status,num_volumes,num_chapters,mean"
    const val MANGA_DETAILS = "$MANGA_SUMMARY,$PICTURES,synopsis,rank,num_list_users"

    internal const val LIST_VALUES = "status,score,num_episodes_watched,start_date,finish_date,updated_at,num_times_rewatched"

    const val LIST_ENTRY = "$SUMMARY,list_status{$LIST_VALUES}"

    // One title as the signed-in user has it on the list.
    const val OWN_ENTRY = "$SUMMARY,my_list_status{$LIST_VALUES}"

    const val PROFILE = "id,name,picture,joined_at,anime_statistics"
}

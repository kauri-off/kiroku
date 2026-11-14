package io.github.kauri_off.kiroku.data.network

// The fields asked of MAL, the same for the clients and for the recorder whose answers their mappers are tested on.
object MalFields {
    // A title as a row holds it: on the list, in search and among a title's related titles and recommendations.
    const val SUMMARY =
        "id,title,main_picture,alternative_titles,media_type,status,num_episodes,start_date,broadcast,mean,rank,genres," +
            "average_episode_duration"

    const val DETAILS =
        "$SUMMARY,end_date,synopsis,start_season,source,studios,rating,statistics," +
            "related_anime{$SUMMARY},recommendations{$SUMMARY}"

    const val LIST_ENTRY = "$SUMMARY,list_status{status,score,num_episodes_watched,start_date,finish_date,updated_at}"
}

package io.github.kauri_off.kiroku.data.recorder

import io.github.kauri_off.kiroku.data.network.MalFields.DETAILS
import io.github.kauri_off.kiroku.data.network.MalFields.LIST_ENTRY
import io.github.kauri_off.kiroku.data.network.MalFields.SUMMARY

private const val LIST = "animelist?fields=$LIST_ENTRY&nsfw=true"

// What the build's recorder asks MAL with the client id alone: one request per line, grouped by the task that reads it.
internal val ClientIdRequests: List<Ask> =
    listOf(
        // M2-14 and M5: rows of a ranking and of a search, with the values a summary may lack.
        Ask("ranking_airing", "anime/ranking?ranking_type=airing&limit=10&fields=$SUMMARY"),
        Ask("ranking_upcoming", "anime/ranking?ranking_type=upcoming&limit=10&fields=$SUMMARY"),
        Ask("search_adult", "anime?q=bible%20black&nsfw=true&limit=10&fields=$SUMMARY"),
        // M2-14: titles.
        Ask("title_airing", "anime/61987?fields=$DETAILS"),
        Ask("title_finished", "anime/1?fields=$DETAILS"),
        Ask("title_film", "anime/32281?fields=$DETAILS"),
        Ask("title_not_yet_aired", "anime/59068?fields=$DETAILS"),
        Ask("title_no_related", "anime/48849?fields=$DETAILS"),
        Ask("title_unknown_episode_count", "anime/21?fields=$DETAILS"),
        Ask("title_adult_nsfw_true", "anime/368?fields=$DETAILS&nsfw=true"),
        Ask("title_adult_nsfw_false", "anime/368?fields=$DETAILS&nsfw=false"),
        Ask("title_adult_nsfw_unset", "anime/368?fields=$DETAILS"),
        Ask("title_unknown", "anime/2?fields=$DETAILS"),
        // M2-15 and M6: lists.
        Ask("list_public", "users/${RecordedUser.Public.standIn}/$LIST&sort=anime_title", pages = 3),
        Ask("list_private", "users/${RecordedUser.Private.standIn}/$LIST&limit=10"),
        Ask("list_unknown_user", "users/kiroku-no-user-0/$LIST&limit=10"),
        // M2-11: the session.
        Ask("token_invalid", "users/@me", accessToken = "made-up-access-token"),
        Ask("refresh_invalid", "token", refreshToken = "made-up-refresh-token"),
    )

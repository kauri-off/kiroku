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
        // M2-18: one title for each genre and code the titles above lack.
        Ask("codes_ecchi", "anime/11617?fields=$DETAILS"),
        Ask("codes_kids", "anime/527?fields=$DETAILS"),
        Ask("codes_girls_love", "anime/37786?fields=$DETAILS"),
        Ask("codes_boys_love", "anime/39533?fields=$DETAILS"),
        Ask("codes_vampire", "anime/777?fields=$DETAILS"),
        Ask("codes_workplace", "anime/25835?fields=$DETAILS"),
        Ask("codes_anthropomorphic", "anime/39195?fields=$DETAILS"),
        Ask("codes_combat_sports", "anime/263?fields=$DETAILS"),
        Ask("codes_delinquents", "anime/42249?fields=$DETAILS"),
        Ask("codes_high_stakes_game", "anime/34933?fields=$DETAILS"),
        Ask("codes_idols_male", "anime/10321?fields=$DETAILS"),
        Ask("codes_iyashikei", "anime/34798?fields=$DETAILS"),
        Ask("codes_magical_sex_shift", "anime/210?fields=$DETAILS"),
        Ask("codes_mahou_shoujo", "anime/9756?fields=$DETAILS"),
        Ask("codes_pets", "anime/6512?fields=$DETAILS"),
        Ask("codes_love_status_quo", "anime/35860?fields=$DETAILS"),
        Ask("codes_survival", "anime/16498?fields=$DETAILS"),
        Ask("codes_alternative_setting", "anime/80?fields=$DETAILS"),
        Ask("codes_parent_story", "anime/5?fields=$DETAILS"),
        Ask("codes_full_story", "anime/4037?fields=$DETAILS"),
        // M2-15 and M6: lists.
        Ask("list_public", "users/${RecordedUser.Public.standIn}/$LIST&sort=anime_title", pages = 3),
        Ask("list_private", "users/${RecordedUser.Private.standIn}/$LIST&limit=10"),
        Ask("list_unknown_user", "users/kiroku-no-user-0/$LIST&limit=10"),
        // M2-11: the session.
        Ask("token_invalid", "users/@me", accessToken = "made-up-access-token"),
        Ask("refresh_invalid", "token", refreshToken = "made-up-refresh-token"),
    )

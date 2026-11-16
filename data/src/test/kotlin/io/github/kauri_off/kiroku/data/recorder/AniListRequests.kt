package io.github.kauri_off.kiroku.data.recorder

// One request of the AniList recorder's list: titles by MAL id, every one of them on MAL's public charts.
internal data class AniListAsk(
    val name: String,
    val ids: List<Int>,
    // False only where the case is AniList's paging, not a title: then no MAL page is recorded for its titles.
    val withMalPages: Boolean = true,
)

// The first 60 titles of MAL's top airing chart on the day of recording, five of them unknown to AniList.
private val TopAiring: List<Int> =
    (
        "61469 61967 21 50250 61987 55809 60988 64534 56524 59953 61603 64398 235 63367 61323 " +
            "44042 65004 63240 51836 64505 63801 64979 47405 966 59088 8687 62651 61578 63293 51289 " +
            "63042 60597 62248 62484 63409 60534 41409 64340 53913 63140 6149 64503 63382 61557 61014 " +
            "54437 53876 63431 60601 36686 63396 9874 59787 63181 49448 51768 60418 48956 58518 62789"
    ).split(' ').map(String::toInt)

// What the build's AniList recorder asks, one case of M3-01 per line; MAL's pages of them are AiringMalPages.
internal val AniListRequests: List<AniListAsk> =
    listOf(
        AniListAsk("airing_weekly", listOf(61987)),
        AniListAsk("releasing_without_times", listOf(62734)),
        AniListAsk("on_a_break", listOf(21)),
        AniListAsk("hundreds_of_episodes", listOf(235)),
        AniListAsk("not_yet_aired_with_time", listOf(62945)),
        AniListAsk("not_yet_aired_without_time", listOf(62841)),
        AniListAsk("finished_last_month", listOf(60636)),
        AniListAsk("totals_differ_by_one", listOf(63323)),
        AniListAsk("totals_differ_by_more", listOf(51039)),
        AniListAsk("unknown_alone", listOf(9874)),
        AniListAsk("unknown_among_others", listOf(9874, 61987, 62734)),
        AniListAsk("more_than_one_page", TopAiring, withMalPages = false),
    )

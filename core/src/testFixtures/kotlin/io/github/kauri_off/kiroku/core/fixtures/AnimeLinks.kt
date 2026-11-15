package io.github.kauri_off.kiroku.core.fixtures

// Addresses of MAL's site: the forms of a title's link the app opens, with their ids, and the ones it refuses.
object AnimeLinks {
    val opened: Map<String, Long> =
        mapOf(
            "https://myanimelist.net/anime/1" to 1,
            "https://myanimelist.net/anime/1/" to 1,
            "https://myanimelist.net/anime/1/Cowboy_Bebop" to 1,
            "https://myanimelist.net/anime/1/Cowboy_Bebop/" to 1,
            "https://www.myanimelist.net/anime/5114/Fullmetal_Alchemist__Brotherhood" to 5114,
            "https://www.myanimelist.net/anime/21/" to 21,
            "https://myanimelist.net/anime/21?utm_source=share" to 21,
            "https://myanimelist.net/anime/21/One_Piece#synopsis" to 21,
            "https://www.myanimelist.net/anime/52991/Sousou_no_Frieren/?q=frieren#top" to 52991,
            "https://myanimelist.net/anime/32281/Kimi_no_Na_wa%E3%80%82" to 32281,
        )

    val refused: List<String> =
        listOf(
            "http://myanimelist.net/anime/1",
            "https://myanimelist.net/manga/1",
            "https://myanimelist.net/profile/someone",
            "https://myanimelist.net/animelist/someone",
            "https://myanimelist.net/anime/1/Cowboy_Bebop/episode",
            "https://myanimelist.net/anime/1/Cowboy_Bebop/episode/1",
            "https://myanimelist.net/anime/1/Cowboy_Bebop/characters",
            "https://myanimelist.net/anime/1/Cowboy_Bebop/reviews",
            "https://myanimelist.net/anime/season/2024/fall",
            "https://myanimelist.net/anime/genre/1/Action",
            "https://myanimelist.net/anime.php?id=1",
            "https://myanimelist.net/anime",
            "https://myanimelist.net/anime/",
            "https://myanimelist.net/anime/0",
            "https://myanimelist.net/anime/01",
            "https://myanimelist.net/anime/-1",
            "https://myanimelist.net/anime/abc",
            "https://myanimelist.net/anime//1",
            "https://myanimelist.net/anime/1//",
            "https://myanimelist.net/anime/1/a%2Fb",
            "https://myanimelist.net",
            "https://m.myanimelist.net/anime/1",
            "https://myanimelist.net.example.com/anime/1",
            "https://example.com/anime/1",
        )

    // Given to the app by the manifest's filter, which cannot tell them from a title's page; the app hands them on.
    val handedOn: List<String> =
        listOf(
            "https://myanimelist.net/anime/99999999999999999999",
            "https://myanimelist.net:8443/anime/1",
            "https://someone@myanimelist.net/anime/1",
            "https://myanimelist.net/anime%2F1",
            "https://myanimelist.net/anime/1%2F2",
            "https://myanimelist.net/anime/%31",
            "https://myanimelist.net/anime/1?ref=a|b",
            "https://myanimelist.net/anime/1/100%_x",
            "https://myanimelist.net/anime/1/Cowboy Bebop",
            "https://myanimelist.net/anime/1/{Bebop}",
            "https://myanimelist.net/anime/1#a^b",
        )

    // Not addresses at all.
    val unreadable: List<String> = listOf("", "myanimelist.net/anime/1", "anime/1")
}

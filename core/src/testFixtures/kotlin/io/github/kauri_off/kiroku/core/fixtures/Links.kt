package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.link.Link
import io.github.kauri_off.kiroku.core.link.USER_NAME_MAX_LENGTH
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.MangaId

// Addresses of MAL's site: the forms of a title's and a profile's link the app opens, with what each opens, and the ones it refuses.
object Links {
    // A name of the most characters the app takes.
    private val longest = "a".repeat(USER_NAME_MAX_LENGTH)

    val titles: Map<String, Link.Title> =
        mapOf(
            "https://myanimelist.net/anime/1" to 1L,
            "https://myanimelist.net/anime/1/" to 1L,
            "https://myanimelist.net/anime/1/Cowboy_Bebop" to 1L,
            "https://myanimelist.net/anime/1/Cowboy_Bebop/" to 1L,
            "https://www.myanimelist.net/anime/5114/Fullmetal_Alchemist__Brotherhood" to 5114L,
            "https://www.myanimelist.net/anime/21/" to 21L,
            "https://myanimelist.net/anime/21?utm_source=share" to 21L,
            "https://myanimelist.net/anime/21/One_Piece#synopsis" to 21L,
            "https://www.myanimelist.net/anime/52991/Sousou_no_Frieren/?q=frieren#top" to 52991L,
            "https://myanimelist.net/anime/32281/Kimi_no_Na_wa%E3%80%82" to 32281L,
        ).mapValues { Link.Title(AnimeId(it.value)) }

    val profiles: Map<String, Link.Profile> =
        mapOf(
            "https://myanimelist.net/profile/sample_viewer" to "sample_viewer",
            "https://myanimelist.net/profile/sample_viewer/" to "sample_viewer",
            "https://www.myanimelist.net/profile/Sample_Viewer" to "Sample_Viewer",
            "https://www.myanimelist.net/profile/quiet-viewer/" to "quiet-viewer",
            "https://myanimelist.net/profile/sample_viewer?utm_source=share" to "sample_viewer",
            "https://myanimelist.net/profile/sample_viewer/#about" to "sample_viewer",
            "https://myanimelist.net/profile/sample%5Fviewer" to "sample_viewer",
            "https://myanimelist.net/profile/0-9" to "0-9",
            "https://myanimelist.net/profile/$longest" to longest,
        ).mapValues { Link.Profile(it.value) }

    val manga: Map<String, Link.MangaTitle> =
        listOf(
            "https://myanimelist.net/manga/1",
            "https://myanimelist.net/manga/1/",
            "https://www.myanimelist.net/manga/1/Monster",
            "https://www.myanimelist.net/manga/1/Monster/?ref=share#top",
        ).associateWith { Link.MangaTitle(MangaId(1)) }

    val opened: Map<String, Link> = titles + profiles + manga

    val refused: List<String> =
        listOf(
            "http://myanimelist.net/anime/1",
            "https://myanimelist.net/manga/0",
            "https://myanimelist.net/manga/01",
            "https://myanimelist.net/manga/1/Monster/reviews",
            "https://myanimelist.net/manga/1/a%2Fb",
            "https://myanimelist.net/manga/1/Monster%2F",
            "https://myanimelist.net/manga/1/Monster%2f",
            "https://myanimelist.net/mangalist/someone",
            "http://myanimelist.net/manga/1",
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
            "https://myanimelist.net/anime/1/title%2F",
            "https://myanimelist.net",
            "https://m.myanimelist.net/anime/1",
            "https://myanimelist.net.example.com/anime/1",
            "https://example.com/anime/1",
            "http://myanimelist.net/profile/sample_viewer",
            "ftp://myanimelist.net/profile/sample_viewer",
            "https://myanimelist.net/profile/sample_viewer/reviews",
            "https://myanimelist.net/profile/sample_viewer/friends/",
            "https://myanimelist.net/profile/sample_viewer//",
            "https://myanimelist.net/profile",
            "https://myanimelist.net/profile/",
            "https://myanimelist.net/profile//",
            "https://myanimelist.net/profile//sample_viewer",
            "https://myanimelist.net/Profile/sample_viewer",
            "https://myanimelist.net/profile.php?username=sample_viewer",
            "https://myanimelist.net/profile/a%2Fb",
            "https://m.myanimelist.net/profile/sample_viewer",
            "https://myanimelist.net.example.com/profile/sample_viewer",
            "https://example.com/profile/sample_viewer",
        )

    // Given to the app by the manifest's filter, which cannot tell them from a page it opens; the app hands them on.
    val handedOn: List<String> =
        listOf(
            "https://myanimelist.net/anime/99999999999999999999",
            "https://myanimelist.net/manga/99999999999999999999",
            "https://myanimelist.net/manga/%31",
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
            "https://myanimelist.net:443/profile/sample_viewer",
            "https://someone@myanimelist.net/profile/sample_viewer",
            "https://myanimelist.net/profile%2Fsample_viewer",
            "https://myanimelist.net/profile/a%2F",
            "https://myanimelist.net/profile/%20",
            "https://myanimelist.net/profile/..",
            "https://myanimelist.net/profile/%2E%2E/",
            "https://myanimelist.net/profile/%FF",
            "https://myanimelist.net/profile/sample viewer",
            "https://myanimelist.net/profile/{sample_viewer}",
            "https://myanimelist.net/profile/@me",
            "https://myanimelist.net/profile/a%20b",
            "https://myanimelist.net/profile/a%3Fb",
            "https://myanimelist.net/profile/%D0%B8%D0%BC%D1%8F",
            "https://myanimelist.net/profile/a.b",
            "https://myanimelist.net/profile/a%00b",
            "https://myanimelist.net/profile/a%0Ab",
            "https://myanimelist.net/profile/%E2%80%AEreganam",
            "https://myanimelist.net/profile/%E2%80%8B",
            "https://myanimelist.net/profile/a%5Cb",
            "https://myanimelist.net/profile/a+b",
            "https://myanimelist.net/profile/%252F",
            "https://myanimelist.net/profile/${longest}x",
        )

    // Not addresses at all.
    val unreadable: List<String> = listOf("", "myanimelist.net/anime/1", "anime/1", "myanimelist.net/profile/sample_viewer")
}

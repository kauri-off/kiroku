package io.github.kauri_off.kiroku.core.fixtures

import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.Broadcast
import io.github.kauri_off.kiroku.core.model.Genre
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.PartialDate
import io.github.kauri_off.kiroku.core.model.Titles
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Year
import java.time.YearMonth
import io.github.kauri_off.kiroku.core.fixtures.FixtureGenres as G

object FixtureAnime {
    // A fixture title's poster address; nothing is served there, the debug build draws stand-in art for it.
    const val POSTER_URL = "https://example.invalid/kiroku/poster/"

    fun posterId(url: String): Long? =
        url
            .removePrefix(POSTER_URL)
            .takeIf { it != url }
            ?.substringBefore('.')
            ?.toLongOrNull()

    val exactToWatch =
        anime(
            id = 900001,
            titles =
                Titles(
                    romaji = "Kagerou Toshokan 2nd Season",
                    english = "The Heat Haze Library Season 2",
                    japanese = "陽炎図書館 第2期",
                    synonyms = listOf("Kagetosho 2", "Heat Haze Library II"),
                ),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 24,
            start = day("2025-10-11"),
            broadcast = Broadcast(DayOfWeek.SATURDAY, LocalTime.of(23, 30)),
            score = 8.71,
            rank = 42,
            genres = listOf(G.drama, G.mystery, G.supernatural),
            duration = minutes(23, 40),
        )

    val exactCaughtUp =
        anime(
            id = 900002,
            titles = Titles("Shiokaze Rhapsody", "Sea Breeze Rhapsody", "潮風ラプソディ"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 12,
            start = day("2025-11-20"),
            broadcast = Broadcast(DayOfWeek.THURSDAY, LocalTime.of(0, 0)),
            score = 7.94,
            rank = 812,
            genres = listOf(G.music, G.romance, G.sliceOfLife),
            duration = minutes(24),
        )

    val exactOnBreak =
        anime(
            id = 900003,
            titles = Titles("Gekkou Kishidan", "Moonlight Order", "月光騎士団"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 13,
            start = day("2025-12-05"),
            broadcast = Broadcast(DayOfWeek.FRIDAY, LocalTime.of(22, 0)),
            score = 7.12,
            rank = 3105,
            genres = listOf(G.action, G.fantasy, G.military),
            duration = minutes(24),
        )

    val estimatedToWatch =
        anime(
            id = 900004,
            titles =
                Titles(
                    "Renkinjutsushi no Yuuutsu na Nichijou",
                    "The Alchemist's Gloomy Days",
                    "錬金術師の憂鬱な日常",
                ),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 12,
            start = day("2025-11-05"),
            broadcast = Broadcast(DayOfWeek.WEDNESDAY, LocalTime.of(22, 0)),
            score = 7.48,
            rank = 1890,
            genres = listOf(G.comedy, G.fantasy, G.sliceOfLife),
            duration = minutes(23),
        )

    val estimatedCaughtUp =
        anime(
            id = 900005,
            titles = Titles("Kaze no Tani no Yuubinya", "The Valley Postman", "風の谷の郵便屋"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 12,
            start = day("2025-12-13"),
            broadcast = Broadcast(DayOfWeek.SATURDAY, LocalTime.of(1, 30)),
            score = 7.66,
            rank = 1402,
            genres = listOf(G.adventure, G.fantasy, G.iyashikei),
            duration = minutes(24),
        )

    val airedUnknown =
        anime(
            id = 900006,
            titles = Titles("Meitantei Hayate", "Detective Hayate", "名探偵ハヤテ"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = null,
            start = PartialDate.Month(YearMonth.of(2019, 4)),
            broadcast = null,
            score = 7.85,
            rank = 960,
            genres = listOf(G.comedy, G.detective, G.mystery),
            duration = minutes(24),
        )

    val unknownTotal =
        anime(
            id = 900007,
            titles = Titles("Mugen Chizu no Tankentai", "Cartographers of the Endless Map", "無限地図の探検隊"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = null,
            start = day("2004-10-02"),
            broadcast = Broadcast(DayOfWeek.SATURDAY, LocalTime.of(18, 0)),
            score = 8.69,
            rank = 51,
            genres = listOf(G.action, G.adventure, G.comedy, G.fantasy, G.shounen, G.superPower),
            duration = minutes(24),
        )

    val finishedPartWay =
        anime(
            id = 900008,
            titles =
                Titles(
                    "Sorairo Umidori no Tegami",
                    "Letters from the Sky-Blue Seabird",
                    "空色ウミドリの手紙",
                ),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 24,
            start = day("2022-04-08"),
            score = 8.02,
            rank = 640,
            genres = listOf(G.drama, G.romance, G.sliceOfLife),
            duration = minutes(24),
        )

    val oneShortOfTotal =
        anime(
            id = 900009,
            titles = Titles("Yuki Usagi Kouhai", "My Snow Bunny Junior", "雪うさぎ後輩"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 12,
            start = day("2025-07-05"),
            score = 7.31,
            rank = 2480,
            genres = listOf(G.comedy, G.romance, G.school),
            duration = minutes(23),
        )

    val longTitle =
        anime(
            id = 900010,
            titles =
                Titles(
                    romaji =
                        "Tensei shitara Kyuuketsuki no Megami-sama datta node, Isekai no Katasumi de Nonbiri to " +
                            "Yoru dake Hiraku Kissaten wo Itonamu Koto ni Shimashita",
                    english =
                        "I Was Reborn as a Vampire Goddess, So I Decided to Run a Quiet Night-Only Café in a " +
                            "Corner of Another World",
                    japanese = "転生したら吸血鬼の女神様だったので、異世界の片隅でのんびりと夜だけ開く喫茶店を営むことにしました",
                    synonyms = listOf("Kyuukissa", "Vampire Goddess Café"),
                ),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 13,
            start = day("2025-12-26"),
            broadcast = Broadcast(DayOfWeek.FRIDAY, LocalTime.of(1, 0)),
            score = 6.88,
            rank = 4720,
            genres = listOf(G.comedy, G.fantasy, G.gourmet, G.isekai, G.iyashikei),
            duration = minutes(24),
        )

    val premiereTimeSoon =
        anime(
            id = 900011,
            titles = Titles("Hanabi Shoutengai", "Fireworks Arcade", "花火商店街"),
            type = MediaType.Tv,
            status = AiringStatus.NotYetAired,
            malEpisodes = 12,
            start = day("2026-01-18"),
            broadcast = Broadcast(DayOfWeek.SUNDAY, LocalTime.of(0, 30)),
            genres = listOf(G.comedy, G.sliceOfLife),
            duration = null,
        )

    val premiereTimeLater =
        anime(
            id = 900012,
            titles = Titles("Kuroneko Tantei Club", "Black Cat Detective Club", "黒猫探偵クラブ"),
            type = MediaType.Ona,
            status = AiringStatus.NotYetAired,
            malEpisodes = 10,
            start = day("2026-01-28"),
            broadcast = Broadcast(DayOfWeek.WEDNESDAY, LocalTime.of(21, 0)),
            genres = listOf(G.comedy, G.detective, G.mystery),
            duration = null,
        )

    val premiereDate =
        anime(
            id = 900013,
            titles = Titles("Sakura Mau Kouen de", "In the Park Where Cherry Blossoms Dance", "桜舞う公園で"),
            type = MediaType.Tv,
            status = AiringStatus.NotYetAired,
            malEpisodes = 12,
            start = day("2026-04-04"),
            genres = listOf(G.drama, G.romance),
            duration = null,
        )

    val premiereMonth =
        anime(
            id = 900014,
            titles = Titles("Seiun Ressha no Yoru ni", "On the Night of the Nebula Express", "星雲列車の夜に"),
            type = MediaType.Tv,
            status = AiringStatus.NotYetAired,
            malEpisodes = null,
            start = PartialDate.Month(YearMonth.of(2026, 7)),
            genres = listOf(G.drama, G.fantasy, G.sciFi),
            duration = null,
        )

    val premiereYear =
        anime(
            id = 900015,
            titles = Titles("Kagerou Toshokan 3rd Season", "The Heat Haze Library Season 3", "陽炎図書館 第3期"),
            type = null,
            status = AiringStatus.NotYetAired,
            malEpisodes = null,
            start = PartialDate.OnlyYear(Year.of(2027)),
            genres = listOf(G.drama, G.mystery, G.supernatural),
            duration = null,
        )

    val premiereUnannounced =
        anime(
            id = 900016,
            titles = Titles("Mahou Shoujo Kotone: Shin Series", japanese = "魔法少女ことね 新シリーズ"),
            type = MediaType.Tv,
            status = AiringStatus.NotYetAired,
            malEpisodes = null,
            start = null,
            poster = false,
            genres = emptyList(),
            duration = null,
        )

    val plannedAiring =
        anime(
            id = 900017,
            titles = Titles("Tsurugi no Uta", "Song of the Sword", "剣の詩"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 12,
            start = day("2025-12-12"),
            broadcast = Broadcast(DayOfWeek.FRIDAY, LocalTime.of(23, 0)),
            score = 7.58,
            rank = 1620,
            genres = listOf(G.action, G.drama, G.historical),
            duration = minutes(24),
        )

    val plannedFinished =
        anime(
            id = 900018,
            titles = Titles("Natsu no Owari no Prism", "Prism at Summer's End", "夏の終わりのプリズム"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 26,
            start = day("2019-07-06"),
            score = 8.24,
            rank = 402,
            genres = listOf(G.drama, G.romance, G.supernatural),
            duration = minutes(24),
        )

    val prequel =
        anime(
            id = 900019,
            titles = Titles("Kagerou Toshokan", "The Heat Haze Library", "陽炎図書館"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 24,
            start = day("2023-04-07"),
            score = 8.55,
            rank = 98,
            genres = listOf(G.drama, G.mystery, G.supernatural),
            duration = minutes(24),
        )

    val film =
        anime(
            id = 900020,
            titles = Titles("Yoake no Ressha", "The Dawn Train", "夜明けの列車"),
            type = MediaType.Movie,
            status = AiringStatus.Finished,
            malEpisodes = 1,
            start = day("2024-08-23"),
            score = 8.38,
            rank = 230,
            genres = listOf(G.awardWinning, G.drama, G.fantasy),
            duration = minutes(107),
        )

    val completedUnscored =
        anime(
            id = 900021,
            titles = Titles("Neko to Majo no Ichinen", "A Year with a Cat and a Witch", "猫と魔女の一年"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 12,
            start = day("2021-10-02"),
            score = 7.21,
            rank = 2790,
            genres = listOf(G.fantasy, G.iyashikei, G.sliceOfLife),
            duration = minutes(24),
        )

    val completedOva =
        anime(
            id = 900022,
            titles = Titles("Kagami no Kuni no Alice-tachi", "The Alices Through the Looking Glass", "鏡の国のアリスたち"),
            type = MediaType.Ova,
            status = AiringStatus.Finished,
            malEpisodes = 4,
            start = day("2018-02-21"),
            score = 7.97,
            rank = 790,
            genres = listOf(G.fantasy, G.mystery, G.psychological),
            duration = minutes(28),
        )

    val completedOna =
        anime(
            id = 900023,
            titles = Titles("Beranda Camp Nisshi", null, "ベランダキャンプ日誌"),
            type = MediaType.Ona,
            status = AiringStatus.Finished,
            malEpisodes = 8,
            start = day("2024-01-10"),
            score = 6.95,
            rank = 4180,
            genres = listOf(G.comedy, G.iyashikei, G.sliceOfLife),
            duration = minutes(4),
        )

    val completedSpecial =
        anime(
            id = 900024,
            titles = Titles("Shiokaze Rhapsody: Hatsuyume", null, "潮風ラプソディ 初夢"),
            type = MediaType.Special,
            status = AiringStatus.Finished,
            malEpisodes = 1,
            start = day("2025-01-01"),
            score = 7.40,
            rank = 2010,
            genres = listOf(G.music, G.romance),
            duration = minutes(25),
        )

    val onHoldAiring =
        anime(
            id = 900025,
            titles = Titles("Tetsu no Ookami", "Wolf of Iron", "鉄の狼"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 24,
            start = day("2025-08-28"),
            broadcast = Broadcast(DayOfWeek.THURSDAY, LocalTime.of(23, 0)),
            score = 8.12,
            rank = 520,
            genres = listOf(G.action, G.mecha, G.military, G.sciFi),
            duration = minutes(24),
        )

    val onHoldFinished =
        anime(
            id = 900026,
            titles = Titles("Hakoniwa no Kuni", "The Miniature Garden Kingdom", "箱庭の国"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 13,
            start = day("2020-01-11"),
            score = 7.03,
            rank = 3500,
            genres = listOf(G.adventure, G.fantasy),
            duration = minutes(24),
        )

    val onHoldOva =
        anime(
            id = 900027,
            titles = Titles("Kissaten Arashi OVA", null, "喫茶店嵐 OVA"),
            type = MediaType.Ova,
            status = AiringStatus.Finished,
            malEpisodes = 2,
            start = day("2017-11-29"),
            genres = listOf(G.comedy, G.gourmet),
            duration = minutes(26),
        )

    val droppedFinished =
        anime(
            id = 900028,
            titles =
                Titles(
                    "Ikai Ryouriban Saikyou Densetsu",
                    "Legend of the Strongest Cook of the Other Realm",
                    "異界料理番最強伝説",
                ),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 12,
            start = day("2023-10-06"),
            score = 6.12,
            rank = 8400,
            genres = listOf(G.comedy, G.fantasy, G.gourmet, G.isekai),
            duration = minutes(24),
        )

    val droppedEstimated =
        anime(
            id = 900029,
            titles = Titles("Bokura no Saigo no Natsuyasumi", "Our Last Summer Break", "僕らの最後の夏休み"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 12,
            start = day("2025-11-14"),
            broadcast = Broadcast(DayOfWeek.FRIDAY, LocalTime.of(0, 0)),
            score = 6.74,
            rank = 5140,
            genres = listOf(G.drama, G.romance, G.school),
            duration = minutes(24),
        )

    val droppedMovie =
        anime(
            id = 900030,
            titles = Titles("Kikai Shinwa: The Movie", "Machine Myth: The Movie", "機械神話 劇場版"),
            type = MediaType.Movie,
            status = AiringStatus.Finished,
            malEpisodes = 1,
            start = day("2022-06-17"),
            score = 6.48,
            rank = 12_480,
            genres = listOf(G.action, G.mecha, G.sciFi),
            duration = minutes(112),
        )

    val notOnListAiring =
        anime(
            id = 900031,
            titles = Titles("Hikari no Kuni no Kishi-tachi", "Knights of the Land of Light", "光の国の騎士たち"),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 12,
            start = day("2025-11-30"),
            broadcast = Broadcast(DayOfWeek.SUNDAY, LocalTime.of(22, 0)),
            score = 7.77,
            rank = 1150,
            genres = listOf(G.action, G.adventure, G.drama, G.fantasy, G.military, G.mythology, G.shounen),
            duration = minutes(24),
        )

    val exactCaughtUpLater =
        anime(
            id = 900042,
            titles =
                Titles(
                    romaji = "Tenkuu-Toshi-Rondenia-no-Kuuchuu-Teien-to-Mahoutsukai-tachi",
                    english = "Rondenia, City of the Sky: The Sorcerers of the Floating Garden",
                    japanese = "天空都市ロンデニアの空中庭園と魔法使いたち",
                ),
            type = MediaType.Tv,
            status = AiringStatus.Airing,
            malEpisodes = 12,
            start = day("2025-12-29"),
            broadcast = Broadcast(DayOfWeek.MONDAY, LocalTime.of(23, 0)),
            score = 7.29,
            rank = 2_356,
            genres = listOf(G.adventure, G.fantasy, G.romance),
            duration = minutes(24),
        )

    val relatedParentStory =
        anime(
            id = 900032,
            titles = Titles("Kagerou Toshokan: Hajimari no Shou", "The Heat Haze Library: The First Chapter"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 12,
            start = day("2021-01-09"),
            score = 7.88,
            rank = 900,
            genres = listOf(G.drama, G.mystery, G.supernatural),
            duration = minutes(24),
        )

    val relatedFullStory =
        anime(
            id = 900033,
            titles = Titles("Kagerou Toshokan: Kanzenban", "The Heat Haze Library: Complete Edition"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 26,
            start = day("2024-10-05"),
            score = 8.10,
            rank = 560,
            genres = listOf(G.drama, G.mystery, G.supernatural),
            duration = minutes(24),
        )

    val relatedSideStoryOva =
        anime(
            id = 900034,
            titles = Titles("Kagerou Toshokan: Shishoshitsu no Himitsu", "The Heat Haze Library: The Librarian's Secret"),
            type = MediaType.Ova,
            status = AiringStatus.Finished,
            malEpisodes = 2,
            start = day("2024-03-27"),
            score = 7.92,
            rank = 860,
            genres = listOf(G.mystery, G.supernatural),
            duration = minutes(27),
        )

    val relatedSideStorySpecial =
        anime(
            id = 900035,
            titles = Titles("Kagerou Toshokan: Natsuyasumi Tokubetsu-hen", "The Heat Haze Library: Summer Special"),
            type = MediaType.Special,
            status = AiringStatus.Finished,
            malEpisodes = 1,
            start = day("2025-08-13"),
            score = 7.60,
            rank = 1500,
            genres = listOf(G.comedy, G.supernatural),
            duration = minutes(24),
        )

    val relatedSpinOff =
        anime(
            id = 900036,
            titles = Titles("Kagerou Toshokan Petit", "The Heat Haze Library Petit"),
            type = MediaType.Ona,
            status = AiringStatus.Finished,
            malEpisodes = 24,
            start = day("2024-01-12"),
            score = 6.90,
            rank = 4600,
            genres = listOf(G.comedy, G.sliceOfLife),
            duration = minutes(3),
        )

    val relatedAlternativeSetting =
        anime(
            id = 900037,
            titles = Titles("Kagerou Gakuen", "Heat Haze Academy"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 12,
            start = day("2022-07-08"),
            score = 7.05,
            rank = 3400,
            genres = listOf(G.comedy, G.romance, G.school),
            duration = minutes(24),
        )

    val relatedAlternativeVersion =
        anime(
            id = 900038,
            titles = Titles("Kagerou Toshokan Movie: Hoshi no Kioku", "The Heat Haze Library the Movie: Memory of Stars"),
            type = MediaType.Movie,
            status = AiringStatus.Finished,
            malEpisodes = 1,
            start = day("2025-02-14"),
            score = 8.30,
            rank = 260,
            genres = listOf(G.drama, G.mystery, G.supernatural),
            duration = minutes(118),
        )

    val relatedSummary =
        anime(
            id = 900039,
            titles = Titles("Kagerou Toshokan: Soushuuhen", "The Heat Haze Library: Recap"),
            type = MediaType.Special,
            status = AiringStatus.Finished,
            malEpisodes = 1,
            start = day("2025-09-27"),
            score = 6.70,
            rank = 5300,
            genres = listOf(G.drama, G.mystery),
            duration = minutes(24),
        )

    val relatedOther =
        anime(
            id = 900040,
            titles = Titles("Kagerou Toshokan x Shiokaze Rhapsody: Collaboration MV"),
            type = MediaType.Music,
            status = AiringStatus.Finished,
            malEpisodes = 1,
            start = day("2025-06-01"),
            score = 6.95,
            rank = null,
            genres = listOf(G.music),
            duration = minutes(5),
        )

    val relatedCharacter =
        anime(
            id = 900041,
            titles = Titles("Hoshi no Shoshi-tachi", "The Star Librarians"),
            type = MediaType.Tv,
            status = AiringStatus.Finished,
            malEpisodes = 12,
            start = day("2020-10-03"),
            score = 7.36,
            rank = 2200,
            genres = listOf(G.fantasy, G.mystery),
            duration = minutes(24),
        )

    val all: List<Anime> =
        listOf(
            exactToWatch,
            exactCaughtUp,
            exactCaughtUpLater,
            exactOnBreak,
            estimatedToWatch,
            estimatedCaughtUp,
            airedUnknown,
            unknownTotal,
            finishedPartWay,
            oneShortOfTotal,
            longTitle,
            premiereTimeSoon,
            premiereTimeLater,
            premiereDate,
            premiereMonth,
            premiereYear,
            premiereUnannounced,
            plannedAiring,
            plannedFinished,
            prequel,
            film,
            completedUnscored,
            completedOva,
            completedOna,
            completedSpecial,
            onHoldAiring,
            onHoldFinished,
            onHoldOva,
            droppedFinished,
            droppedEstimated,
            droppedMovie,
            notOnListAiring,
            relatedParentStory,
            relatedFullStory,
            relatedSideStoryOva,
            relatedSideStorySpecial,
            relatedSpinOff,
            relatedAlternativeSetting,
            relatedAlternativeVersion,
            relatedSummary,
            relatedOther,
            relatedCharacter,
        )

    private fun day(iso: String) = PartialDate.Day(LocalDate.parse(iso))

    private fun anime(
        id: Long,
        titles: Titles,
        type: MediaType?,
        status: AiringStatus,
        malEpisodes: Int?,
        start: PartialDate?,
        broadcast: Broadcast? = null,
        score: Double? = null,
        rank: Int? = null,
        genres: List<Genre>,
        duration: Duration?,
        poster: Boolean = true,
    ) = Anime(
        id = AnimeId(id),
        titles = titles,
        posterUrl = if (poster) "$POSTER_URL$id.jpg" else null,
        mediaType = type,
        airingStatus = status,
        malEpisodes = malEpisodes,
        startDate = start,
        broadcast = broadcast,
        meanScore = score,
        rank = rank,
        genres = genres,
        averageEpisodeDuration = duration,
    )

    private fun minutes(
        minutes: Long,
        seconds: Long = 0,
    ): Duration = Duration.ofMinutes(minutes).plusSeconds(seconds)
}

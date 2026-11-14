package io.github.kauri_off.kiroku.text

import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.AgeRating
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.AnimeSeason
import io.github.kauri_off.kiroku.core.model.MediaType
import io.github.kauri_off.kiroku.core.model.RelationType
import io.github.kauri_off.kiroku.core.model.Season
import io.github.kauri_off.kiroku.core.model.Source
import io.github.kauri_off.kiroku.core.model.malCodeAsText

// MAL's values as interface text (ARCHITECTURE section 10): [string] reads a resource; a value the app does not know is its code as words.

inline fun MediaType.named(string: (Int) -> String): String =
    when (this) {
        MediaType.Tv -> string(R.string.common_type_tv)
        MediaType.TvSpecial -> string(R.string.common_type_tv_special)
        MediaType.Movie -> string(R.string.common_type_movie)
        MediaType.Ova -> string(R.string.common_type_ova)
        MediaType.Ona -> string(R.string.common_type_ona)
        MediaType.Special -> string(R.string.common_type_special)
        MediaType.Music -> string(R.string.common_type_music)
        MediaType.Commercial -> string(R.string.common_type_commercial)
        MediaType.PromotionalVideo -> string(R.string.common_type_promotional)
        is MediaType.Unrecognized -> malCodeAsText(malCode)
    }

inline fun RelationType.named(string: (Int) -> String): String =
    when (this) {
        RelationType.Prequel -> string(R.string.common_relation_prequel)
        RelationType.Sequel -> string(R.string.common_relation_sequel)
        RelationType.ParentStory -> string(R.string.common_relation_parent_story)
        RelationType.FullStory -> string(R.string.common_relation_full_story)
        RelationType.SideStory -> string(R.string.common_relation_side_story)
        RelationType.SpinOff -> string(R.string.common_relation_spin_off)
        RelationType.AlternativeSetting -> string(R.string.common_relation_alternative_setting)
        RelationType.AlternativeVersion -> string(R.string.common_relation_alternative_version)
        RelationType.Summary -> string(R.string.common_relation_summary)
        RelationType.Other -> string(R.string.common_relation_other)
        RelationType.Character -> string(R.string.common_relation_character)
        is RelationType.Unrecognized -> malCodeAsText(malCode)
    }

inline fun Source.named(string: (Int) -> String): String =
    when (this) {
        Source.Original -> string(R.string.titlesub_source_original)
        Source.Manga -> string(R.string.titlesub_source_manga)
        Source.FourKomaManga -> string(R.string.titlesub_source_4_koma_manga)
        Source.WebManga -> string(R.string.titlesub_source_web_manga)
        Source.DigitalManga -> string(R.string.titlesub_source_digital_manga)
        Source.Novel -> string(R.string.titlesub_source_novel)
        Source.LightNovel -> string(R.string.titlesub_source_light_novel)
        Source.WebNovel -> string(R.string.titlesub_source_web_novel)
        Source.VisualNovel -> string(R.string.titlesub_source_visual_novel)
        Source.Game -> string(R.string.titlesub_source_game)
        Source.CardGame -> string(R.string.titlesub_source_card_game)
        Source.Book -> string(R.string.titlesub_source_book)
        Source.PictureBook -> string(R.string.titlesub_source_picture_book)
        Source.Radio -> string(R.string.titlesub_source_radio)
        Source.Music -> string(R.string.titlesub_source_music)
        Source.MixedMedia -> string(R.string.titlesub_source_mixed_media)
        Source.Other -> string(R.string.titlesub_source_other)
        is Source.Unrecognized -> malCodeAsText(malCode)
    }

inline fun AgeRating.named(string: (Int) -> String): String =
    when (this) {
        AgeRating.AllAges -> string(R.string.titlesub_rating_all_ages)
        AgeRating.Children -> string(R.string.titlesub_rating_children)
        AgeRating.Teens13 -> string(R.string.titlesub_rating_teens_13)
        AgeRating.Violence17 -> string(R.string.titlesub_rating_violence_17)
        AgeRating.MildNudity -> string(R.string.titlesub_rating_mild_nudity)
        AgeRating.Hentai -> string(R.string.titlesub_rating_hentai)
        is AgeRating.Unrecognized -> malCodeAsText(malCode)
    }

inline fun AiringStatus.named(string: (Int) -> String): String =
    when (this) {
        AiringStatus.NotYetAired -> string(R.string.common_not_yet_aired)
        AiringStatus.Airing -> string(R.string.mal_airing)
        AiringStatus.Finished -> string(R.string.mal_finished)
    }

// "Fall 2024": [string] reads a resource with the year.
inline fun AnimeSeason.named(string: (Int, Int) -> String): String =
    when (season) {
        Season.Winter -> string(R.string.mal_season_winter, year)
        Season.Spring -> string(R.string.mal_season_spring, year)
        Season.Summer -> string(R.string.mal_season_summer, year)
        Season.Fall -> string(R.string.mal_season_fall, year)
    }

package io.github.kauri_off.kiroku.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.kauri_off.kiroku.R
import io.github.kauri_off.kiroku.core.model.Genre

// A genre's name in the app's language; a genre the app has no name for keeps MAL's (ARCHITECTURE section 10).
@Composable
fun genreName(genre: Genre): String = genreNameRes(genre.id)?.let { stringResource(it) } ?: genre.malName

@StringRes
fun genreNameRes(malId: Int): Int? = genreNames[malId]

private val genreNames: Map<Int, Int> =
    mapOf(
        1 to R.string.genre_action,
        2 to R.string.genre_adventure,
        4 to R.string.genre_comedy,
        5 to R.string.genre_avant_garde,
        6 to R.string.genre_mythology,
        7 to R.string.genre_mystery,
        8 to R.string.genre_drama,
        10 to R.string.genre_fantasy,
        13 to R.string.genre_historical,
        14 to R.string.genre_horror,
        18 to R.string.genre_mecha,
        19 to R.string.genre_music,
        22 to R.string.genre_romance,
        23 to R.string.genre_school,
        24 to R.string.genre_sci_fi,
        27 to R.string.genre_shounen,
        29 to R.string.genre_space,
        30 to R.string.genre_sports,
        31 to R.string.genre_super_power,
        36 to R.string.genre_slice_of_life,
        37 to R.string.genre_supernatural,
        38 to R.string.genre_military,
        39 to R.string.genre_detective,
        40 to R.string.genre_psychological,
        41 to R.string.genre_suspense,
        42 to R.string.genre_seinen,
        46 to R.string.genre_award_winning,
        47 to R.string.genre_gourmet,
        62 to R.string.genre_isekai,
        63 to R.string.genre_iyashikei,
    )

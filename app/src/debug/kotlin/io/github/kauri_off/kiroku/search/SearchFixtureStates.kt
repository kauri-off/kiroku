package io.github.kauri_off.kiroku.search

import androidx.compose.foundation.text.input.TextFieldState
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.FixtureSet
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.model.AiringStatus
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.BrowseList

// Search's and the browse lists' states over the fixture set, for previews, screenshots and the debug build.
object SearchFixtureStates {
    private val set = Fixtures.reference
    private val standIn = standIn(set)

    // Searches [set]'s titles in every language, as MAL does, and browses them as the demo server does.
    fun standIn(set: FixtureSet): SearchStandIn {
        val entries = set.entries.associateBy { it.anime.id }

        fun hit(
            anime: Anime,
            rank: Int? = null,
        ) = SearchHit(anime, entries[anime.id], rank)
        return object : SearchStandIn {
            override val recent: List<String> get() = SearchFixtureStates.recent

            override fun search(query: String): List<SearchHit> =
                set.anime
                    .filter { anime ->
                        with(anime.titles) { (listOfNotNull(romaji, english, japanese) + synonyms).any { it.contains(query, ignoreCase = true) } }
                    }.map { hit(it) }

            override fun browse(list: BrowseList): List<SearchHit> =
                when (list) {
                    BrowseList.ThisSeason -> set.anime.filter { it.airingStatus != AiringStatus.Finished }.map { hit(it) }
                    BrowseList.TopAiring ->
                        byRank(set.anime.filter { it.airingStatus == AiringStatus.Airing }).mapIndexed { i, anime -> hit(anime, i + 1) }
                    BrowseList.TopRated -> byRank(set.anime).map { hit(it, it.rank) }
                    BrowseList.MostPopular ->
                        set.anime
                            .sortedByDescending { set.details[it.id]?.statistics?.members ?: 0 }
                            .mapIndexed { i, anime -> hit(anime, i + 1) }
                }
        }
    }

    private fun byRank(anime: List<Anime>) = anime.filter { it.rank != null }.sortedBy { it.rank }

    private fun firstWord(anime: Anime) = anime.titles.romaji.substringBefore(' ')

    // Five searches a user might have typed, newest first: a word, a whole title, an English title.
    val recent: List<String> =
        listOf(
            firstWord(FixtureAnime.exactCaughtUp),
            FixtureAnime.airedUnknown.titles.romaji,
            checkNotNull(FixtureAnime.notOnListAiring.titles.english),
            firstWord(FixtureAnime.premiereTimeLater),
            firstWord(FixtureAnime.film),
        )

    // "Kagerou": a title being watched, a planned one, one completed at its total, and titles not on the list.
    val query: String = firstWord(FixtureAnime.exactToWatch)

    // What a user has typed so far of "Shiokaze".
    private val typed: String = firstWord(FixtureAnime.exactCaughtUp).take(TYPED_LETTERS)

    // A query of digits alone: the id of a title, which no title's name contains.
    private val digits: String =
        FixtureAnime.notOnListAiring.id.value
            .toString()

    // Two titles' words together, which no title holds.
    private val nothing: String = firstWord(FixtureAnime.exactToWatch) + ' ' + firstWord(FixtureAnime.film)

    fun rows(query: String): List<ResultRow> = standIn.search(query).map(::row)

    fun browseRows(list: BrowseList): List<ResultRow> = standIn.browse(list).map(::row)

    private fun row(hit: SearchHit) = resultRow(hit.anime, hit.entry?.values, TitleLanguage.Romaji, hit.rank)

    fun state(
        text: String,
        content: SearchContent,
        expanded: Boolean = false,
        sessionExpired: Boolean = false,
    ) = SearchState(TextFieldState(text), expanded, content, sessionExpired)

    private fun results(
        query: String,
        more: More = More.None,
    ) = state(query, SearchContent.Results(query, Paged.Shown(rows(query), more)))

    val start = state("", SearchContent.Start(recent))

    val startNoRecent = state("", SearchContent.Start(emptyList()))

    val searchView = state("", SearchContent.Start(recent), expanded = true)

    val typing = state(typed, SearchContent.Results(typed, Paged.Shown(rows(typed))), expanded = true)

    val results = results(query)

    val digitsQuery = state(digits, SearchContent.Results(digits, Paged.Shown(rows(digits))))

    val noResults = state(nothing, SearchContent.Results(nothing, Paged.Shown(emptyList())))

    val loading = state(query, SearchContent.Results(query, Paged.Loading))

    val loadingMore = results(query, More.Loading)

    val error = state(query, SearchContent.Results(query, Paged.Failed(DataError.NoConnection)))

    val moreFailed = results(query, More.Failed(DataError.Refused))

    val sessionExpired = state("", SearchContent.Start(recent), sessionExpired = true)

    fun browse(
        list: BrowseList,
        more: More = More.None,
    ) = BrowseState(list, Paged.Shown(browseRows(list), more))

    val browseLoading = BrowseState(BrowseList.TopAiring, Paged.Loading)

    val browseEmpty = BrowseState(BrowseList.ThisSeason, Paged.Shown(emptyList()))

    val browseError = BrowseState(BrowseList.TopAiring, Paged.Failed(DataError.NoConnection))

    private const val TYPED_LETTERS = 5
}

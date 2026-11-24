package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// The demo's choices, kept as long as its process, as the rest of the demo is.
class DemoPreferences : PreferencesRepository {
    private val sorts = MutableStateFlow(ListStatus.entries.associateWith(TabSort::defaultFor))
    private val opened = MutableStateFlow(emptyList<String>())

    private val language = MutableStateFlow(TitleLanguage.Romaji)
    private val adult = MutableStateFlow(false)

    override fun observeTitleLanguage(): Flow<TitleLanguage> = language

    override fun observeAdultInSearch(): Flow<Boolean> = adult

    override suspend fun setTitleLanguage(language: TitleLanguage): Outcome<Unit> {
        this.language.value = language
        return Outcome.Success(Unit)
    }

    override suspend fun setAdultInSearch(show: Boolean): Outcome<Unit> {
        adult.value = show
        return Outcome.Success(Unit)
    }

    override fun observeListSorts(): Flow<Map<ListStatus, TabSort>> = sorts

    override suspend fun setListSort(
        status: ListStatus,
        sort: TabSort,
    ) = sorts.update { it + (status to sort) }

    override fun observeOpenedProfiles(): Flow<List<String>> = opened

    override suspend fun updateOpenedProfiles(change: (List<String>) -> List<String>) = opened.update(change)

    // As a sign-out does.
    fun forgetOpenedProfiles() {
        opened.value = emptyList()
    }
}

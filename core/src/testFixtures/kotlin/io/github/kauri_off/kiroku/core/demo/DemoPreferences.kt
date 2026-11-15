package io.github.kauri_off.kiroku.core.demo

import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// The demo's choices, kept as long as its process, as the rest of the demo is.
class DemoPreferences : PreferencesRepository {
    private val sorts = MutableStateFlow(ListStatus.entries.associateWith(TabSort::defaultFor))

    override fun observeListSorts(): Flow<Map<ListStatus, TabSort>> = sorts

    override suspend fun setListSort(
        status: ListStatus,
        sort: TabSort,
    ) = sorts.update { it + (status to sort) }
}

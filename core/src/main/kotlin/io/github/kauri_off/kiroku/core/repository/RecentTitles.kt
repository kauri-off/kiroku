package io.github.kauri_off.kiroku.core.repository

import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

data class RecentTitle(
    val key: RecentTitleId,
    val titles: Titles,
    val safeForSearch: Boolean?,
)

sealed interface RecentTitleId {
    val value: Long

    data class Anime(
        override val value: Long,
    ) : RecentTitleId

    data class Manga(
        override val value: Long,
    ) : RecentTitleId
}

data class RecentTitleScope(
    val owner: SignInId?,
    val key: String,
)

data class RecentTitleVisit(
    val title: RecentTitle,
    val order: Long,
) {
    val key: RecentTitleId get() = title.key
}

data class RecentTitleRemoval(
    val scope: RecentTitleScope,
    val key: RecentTitleId,
    val order: Long,
    val through: Long? = null,
) {
    fun matches(visit: RecentTitleVisit): Boolean = key == visit.key && order == visit.order
}

sealed interface RecentTitleRemovalResult {
    data object Removed : RecentTitleRemovalResult

    data object Stale : RecentTitleRemovalResult

    data class Failed(
        val through: Long? = null,
    ) : RecentTitleRemovalResult
}

interface RecentTitles {
    fun observeScope(): Flow<RecentTitleScope?>

    fun observe(scope: RecentTitleScope): Flow<List<RecentTitleVisit>>

    suspend fun remove(removal: RecentTitleRemoval): RecentTitleRemovalResult

    suspend fun reserveVisit(scope: RecentTitleScope): Long?

    suspend fun record(
        scope: RecentTitleScope,
        title: RecentTitle,
        visit: Long,
    )

    companion object {
        const val LIMIT = 3
    }
}

object NoRecentTitles : RecentTitles {
    override fun observeScope() = emptyFlow<RecentTitleScope?>()

    override fun observe(scope: RecentTitleScope) = flowOf(emptyList<RecentTitleVisit>())

    override suspend fun remove(removal: RecentTitleRemoval) = RecentTitleRemovalResult.Stale

    override suspend fun reserveVisit(scope: RecentTitleScope): Long? = null

    override suspend fun record(
        scope: RecentTitleScope,
        title: RecentTitle,
        visit: Long,
    ) = Unit
}

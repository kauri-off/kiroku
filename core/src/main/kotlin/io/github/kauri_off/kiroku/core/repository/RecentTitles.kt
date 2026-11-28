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

interface RecentTitles {
    fun observeScope(): Flow<RecentTitleScope?>

    fun observe(scope: RecentTitleScope): Flow<List<RecentTitle>>

    suspend fun reserveVisit(scope: RecentTitleScope): Long?

    suspend fun record(
        scope: RecentTitleScope,
        title: RecentTitle,
        visit: Long,
    )

    companion object {
        const val LIMIT = 10
    }
}

object NoRecentTitles : RecentTitles {
    override fun observeScope() = emptyFlow<RecentTitleScope?>()

    override fun observe(scope: RecentTitleScope) = flowOf(emptyList<RecentTitle>())

    override suspend fun reserveVisit(scope: RecentTitleScope): Long? = null

    override suspend fun record(
        scope: RecentTitleScope,
        title: RecentTitle,
        visit: Long,
    ) = Unit
}

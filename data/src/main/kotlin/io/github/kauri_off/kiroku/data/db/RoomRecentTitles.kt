package io.github.kauri_off.kiroku.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.RecentTitle
import io.github.kauri_off.kiroku.core.repository.RecentTitleId
import io.github.kauri_off.kiroku.core.repository.RecentTitleScope
import io.github.kauri_off.kiroku.core.repository.RecentTitles
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.fileRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Entity(tableName = "recent_title", primaryKeys = ["manga", "title_id"])
internal data class RecentTitleRow(
    val visit: Long,
    val owner: String,
    val manga: Boolean,
    @ColumnInfo(name = "title_id") val id: Long,
    val romaji: String,
    val english: String?,
    val japanese: String?,
    @ColumnInfo(name = "safe_for_search") val safeForSearch: Boolean?,
) {
    fun toTitle(): RecentTitle =
        RecentTitle(
            if (manga) RecentTitleId.Manga(id) else RecentTitleId.Anime(id),
            Titles(romaji, english, japanese),
            safeForSearch,
        )
}

@Entity(tableName = "recent_visit_sequence")
internal data class RecentVisitSequence(
    @PrimaryKey val id: Int = 0,
    val last: Long,
)

@Dao
internal interface RecentTitleDao {
    @Query(
        "SELECT max(coalesce((SELECT last FROM recent_visit_sequence WHERE id = 0), 0), " +
            "coalesce((SELECT max(visit) FROM recent_title), 0))",
    )
    suspend fun lastVisit(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSequence(sequence: RecentVisitSequence)

    @Query("SELECT * FROM recent_title WHERE owner = :owner ORDER BY visit DESC, manga, title_id")
    fun observe(owner: String): Flow<List<RecentTitleRow>>

    @Query("SELECT * FROM recent_title WHERE owner = :owner AND manga = :manga AND title_id = :id")
    suspend fun read(
        owner: String,
        manga: Boolean,
        id: Long,
    ): RecentTitleRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: RecentTitleRow)

    @Query(
        "DELETE FROM recent_title WHERE (manga, title_id) NOT IN " +
            "(SELECT manga, title_id FROM recent_title ORDER BY visit DESC, manga, title_id LIMIT :limit)",
    )
    suspend fun trim(limit: Int)

    @Query("DELETE FROM recent_title WHERE owner != :owner")
    suspend fun deleteOthers(owner: String)

    @Query("DELETE FROM recent_title WHERE owner = :owner")
    suspend fun delete(owner: String)
}

class RoomRecentTitles(
    private val database: suspend () -> KirokuDatabase,
    private val session: MalSession,
    private val wasRetired: suspend (SignInId) -> Boolean = { false },
    private val unexpected: (Throwable) -> Unit,
) : RecentTitles {
    private fun scopeFor(owner: SessionOwner): RecentTitleScope =
        RecentTitleScope(
            owner.id,
            owner.id?.value ?: guestKey(owner.guestGeneration),
        )

    private fun guestKey(generation: String?) = generation?.let { "guest-$it" } ?: "guest"

    private suspend fun retired(owner: SignInId?): Boolean = owner != null && wasRetired(owner)

    override fun observeScope() =
        session.observeOwner().map { owner ->
            stored { scopeFor(owner).takeUnless { retired(it.owner) } }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(scope: RecentTitleScope) =
        observeScope()
            .flatMapLatest { current ->
                if (current == scope) {
                    flow { emitAll(database().recentTitles().observe(scope.key)) }
                        .map { rows -> if (retired(scope.owner)) emptyList() else rows }
                } else {
                    flowOf(emptyList())
                }
            }.map { rows -> rows.map(RecentTitleRow::toTitle) }
            .catch { error ->
                unexpected(error)
                emit(emptyList())
            }

    override suspend fun reserveVisit(scope: RecentTitleScope): Long? =
        withScope(scope) { db ->
            db.transaction {
                val dao = db.recentTitles()
                val next = dao.lastVisit() + 1
                dao.saveSequence(RecentVisitSequence(last = next))
                next
            }
        }

    private suspend fun <T : Any> withScope(
        scope: RecentTitleScope,
        run: suspend (KirokuDatabase) -> T,
    ): T? =
        withContext(Dispatchers.IO) {
            val owner = session.observeOwner().first()
            if (scopeFor(owner) != scope) return@withContext null
            stored {
                session.withOwner(owner) {
                    if (retired(scope.owner)) return@withOwner null
                    run(database())
                }
            }
        }

    override suspend fun record(
        scope: RecentTitleScope,
        title: RecentTitle,
        visit: Long,
    ) {
        withScope(scope) { db ->
            db.transaction {
                val dao = db.recentTitles()
                val manga = title.key is RecentTitleId.Manga
                val previous = dao.read(scope.key, manga, title.key.value)
                if (previous != null && previous.visit > visit) return@transaction
                dao.deleteOthers(scope.key)
                dao.save(
                    RecentTitleRow(
                        visit,
                        scope.key,
                        manga,
                        title.key.value,
                        title.titles.romaji,
                        title.titles.english,
                        title.titles.japanese,
                        title.safeForSearch ?: previous?.safeForSearch,
                    ),
                )
                dao.trim(RecentTitles.LIMIT)
            }
        }
    }

    suspend fun sweepRetired(hasDatabase: () -> Boolean) {
        session.observeOwner().collectLatest { owner ->
            if (!hasDatabase()) return@collectLatest
            var attempt = 0
            while (stored(report = attempt == 0) {
                    session.withOwner(owner) {
                        val dao = database().recentTitles()
                        val scope = scopeFor(owner)
                        if (retired(scope.owner)) dao.delete(scope.key) else dao.deleteOthers(scope.key)
                    }
                    true
                } == null
            ) {
                delay(fileRetry(attempt++).toMillis())
            }
        }
    }

    suspend fun clear(owner: SignInId): Boolean = stored { database().recentTitles().delete(owner.value) } != null

    private inline fun <T> stored(
        report: Boolean = true,
        run: () -> T,
    ): T? =
        try {
            run()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (report) unexpected(error)
            null
        }
}

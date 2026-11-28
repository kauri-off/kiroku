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
    @ColumnInfo(name = "evicted_through", defaultValue = "0") val evictedThrough: Long,
)

@Entity(tableName = "recent_title_dismissal", primaryKeys = ["owner", "manga", "title_id"])
internal data class RecentTitleDismissal(
    val owner: String,
    val manga: Boolean,
    @ColumnInfo(name = "title_id") val id: Long,
    val visit: Long,
)

@Dao
internal interface RecentTitleDao {
    @Query(
        "SELECT max(coalesce((SELECT last FROM recent_visit_sequence WHERE id = 0), 0), " +
            "coalesce((SELECT max(visit) FROM recent_title), 0))",
    )
    suspend fun lastVisit(): Long

    @Query(
        "INSERT OR REPLACE INTO recent_visit_sequence (id, last, evicted_through) " +
            "VALUES (0, :last, max(:evictedThrough, coalesce((SELECT evicted_through FROM recent_visit_sequence WHERE id = 0), 0)))",
    )
    suspend fun saveSequence(
        last: Long,
        evictedThrough: Long = 0,
    )

    @Query("SELECT evicted_through FROM recent_visit_sequence WHERE id = 0")
    suspend fun evictedThrough(): Long?

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

    @Query("SELECT visit FROM recent_title ORDER BY visit DESC, manga, title_id LIMIT 1 OFFSET :limit")
    suspend fun newestOverflowVisit(limit: Int): Long?

    @Query(
        "DELETE FROM recent_title WHERE (manga, title_id) NOT IN " +
            "(SELECT manga, title_id FROM recent_title ORDER BY visit DESC, manga, title_id LIMIT :limit)",
    )
    suspend fun trim(limit: Int)

    @Query("SELECT visit FROM recent_title_dismissal WHERE owner = :owner AND manga = :manga AND title_id = :id")
    suspend fun dismissedThrough(
        owner: String,
        manga: Boolean,
        id: Long,
    ): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDismissal(dismissal: RecentTitleDismissal)

    @Query("DELETE FROM recent_title WHERE owner = :owner AND manga = :manga AND title_id = :id")
    suspend fun deleteTitle(
        owner: String,
        manga: Boolean,
        id: Long,
    )

    @Query("DELETE FROM recent_title_dismissal WHERE owner != :owner")
    suspend fun deleteOtherDismissals(owner: String)

    @Query("DELETE FROM recent_title_dismissal WHERE owner = :owner")
    suspend fun deleteDismissals(owner: String)

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
                dao.saveSequence(next)
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

    override suspend fun remove(
        scope: RecentTitleScope,
        key: RecentTitleId,
    ): Boolean =
        withScope(scope) { db ->
            db.transaction {
                val dao = db.recentTitles()
                val manga = key is RecentTitleId.Manga
                val cutoff = dao.lastVisit()
                dao.saveSequence(cutoff)
                dao.saveDismissal(RecentTitleDismissal(scope.key, manga, key.value, cutoff))
                dao.deleteTitle(scope.key, manga, key.value)
            }
        } != null

    override suspend fun record(
        scope: RecentTitleScope,
        title: RecentTitle,
        visit: Long,
    ) {
        withScope(scope) { db ->
            db.transaction {
                val dao = db.recentTitles()
                val manga = title.key is RecentTitleId.Manga
                val dismissed = dao.dismissedThrough(scope.key, manga, title.key.value)
                if (dismissed != null && visit <= dismissed) return@transaction
                val previous = dao.read(scope.key, manga, title.key.value)
                val minimum = previous?.visit ?: ((dao.evictedThrough() ?: 0) + 1)
                if (visit < minimum) return@transaction
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
                val overflow = dao.newestOverflowVisit(RecentTitles.LIMIT)
                if (overflow != null) {
                    dao.saveSequence(dao.lastVisit(), overflow)
                    dao.trim(RecentTitles.LIMIT)
                }
            }
        }
    }

    suspend fun sweepRetired(hasDatabase: () -> Boolean) {
        session.observeOwner().collectLatest { owner ->
            if (!hasDatabase()) return@collectLatest
            var attempt = 0
            while (stored(report = attempt == 0) {
                    session.withOwner(owner) {
                        val db = database()
                        val scope = scopeFor(owner)
                        val isRetired = retired(scope.owner)
                        db.transaction {
                            val dao = db.recentTitles()
                            if (isRetired) {
                                dao.delete(scope.key)
                                dao.deleteDismissals(scope.key)
                            } else {
                                dao.deleteOthers(scope.key)
                                dao.deleteOtherDismissals(scope.key)
                            }
                        }
                    }
                    true
                } == null
            ) {
                delay(fileRetry(attempt++).toMillis())
            }
        }
    }

    suspend fun clear(owner: SignInId): Boolean =
        stored {
            val db = database()
            db.transaction {
                db.recentTitles().delete(owner.value)
                db.recentTitles().deleteDismissals(owner.value)
            }
        } != null

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

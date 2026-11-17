package io.github.kauri_off.kiroku.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal abstract class ProfileDao {
    @Query("SELECT * FROM own_profile")
    abstract fun observe(): Flow<List<ProfileRow>>

    @Query("SELECT * FROM own_profile")
    abstract suspend fun stored(): List<ProfileRow>

    @Insert
    protected abstract suspend fun insert(row: ProfileRow)

    @Query("DELETE FROM own_profile")
    abstract suspend fun deleteAll()

    // Every profile but [owner]'s; all of them for no owner.
    @Query("DELETE FROM own_profile WHERE owner IS NOT :owner")
    abstract suspend fun deleteAllBut(owner: String?)

    // Called inside a transaction: the one row is [row].
    suspend fun replace(row: ProfileRow) {
        deleteAll()
        insert(row)
    }
}

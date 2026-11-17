package io.github.kauri_off.kiroku.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.StatedFigures
import io.github.kauri_off.kiroku.core.model.UserProfile
import java.time.Instant
import java.time.LocalDate

// The signed-in user's profile as the last whole answer gave it, for the sign-in it was read as; at most one row.
@Entity(tableName = ProfileRow.TABLE)
internal data class ProfileRow(
    @PrimaryKey val owner: String,
    val name: String,
    val picture: String?,
    val joined: String?,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @Embedded(prefix = "stated_") val stated: StatedColumns?,
) {
    fun toStored(): StoredProfile =
        StoredProfile(SignInId(owner), UserProfile(name, picture, joined?.let(LocalDate::parse), stated?.toFigures()), instantOf(fetchedAt))

    companion object {
        const val TABLE = "own_profile"
    }
}

internal data class StatedColumns(
    val watching: Int?,
    val completed: Int?,
    @ColumnInfo(name = "on_hold") val onHold: Int?,
    val dropped: Int?,
    val planned: Int?,
    val entries: Int?,
    val episodes: Int?,
    val rewatched: Int?,
    @ColumnInfo(name = "days_watched") val daysWatched: Double?,
    @ColumnInfo(name = "mean_score") val meanScore: Double?,
) {
    fun toFigures(): StatedFigures =
        StatedFigures(watching, completed, onHold, dropped, planned, entries, episodes, rewatched, daysWatched, meanScore)
}

private fun StatedFigures.toColumns(): StatedColumns =
    StatedColumns(watching, completed, onHold, dropped, planned, entries, episodes, rewatched, daysWatched, meanScore)

internal data class StoredProfile(
    val owner: SignInId,
    val profile: UserProfile,
    val fetchedAt: Instant,
)

internal fun StoredProfile.toRow(): ProfileRow =
    ProfileRow(owner.value, profile.name, profile.pictureUrl, profile.joined?.toString(), fetchedAt.toColumn(), profile.stated?.toColumns())

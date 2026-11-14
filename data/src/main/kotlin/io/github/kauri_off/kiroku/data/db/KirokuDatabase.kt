package io.github.kauri_off.kiroku.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver

// Everything shown from cache (section 7.1). A change to an entity is a new version with a migration in [migrations].
@Database(
    entities = [
        TitleRow::class,
        TitleDetailsRow::class,
        TitleRelationRow::class,
        TitleRecommendationRow::class,
        ListEntryRow::class,
        ListStateRow::class,
    ],
    version = KirokuDatabase.VERSION,
)
abstract class KirokuDatabase : RoomDatabase() {
    internal abstract fun titles(): TitleDao

    internal abstract fun list(): ListDao

    companion object {
        const val NAME = "kiroku.db"
        const val VERSION = 1

        val migrations: List<Migration> = emptyList()

        // The only way a database of the app is opened: with every migration and no destructive fallback (N10).
        fun open(
            context: Context,
            name: String = NAME,
        ): KirokuDatabase = builder(context, name).build()

        // In memory when it has no name.
        internal fun builder(
            context: Context,
            name: String?,
        ): Builder<KirokuDatabase> {
            val builder =
                if (name == null) {
                    Room.inMemoryDatabaseBuilder(context, KirokuDatabase::class.java)
                } else {
                    Room.databaseBuilder(context, KirokuDatabase::class.java, name)
                }
            return builder.setDriver(AndroidSQLiteDriver()).addMigrations(*migrations.toTypedArray())
        }
    }
}

// One transaction around the calls made in [block]: all of them or none.
internal suspend fun <T> KirokuDatabase.transaction(block: suspend () -> T): T = useWriterConnection { it.immediateTransaction { block() } }

// SQLite before 3.32 takes 999 variables in a statement.
internal const val MAX_VARIABLES = 900

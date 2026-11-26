package io.github.kauri_off.kiroku.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.migration.Migration
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL

// Everything shown from cache (section 7.1). A change to an entity is a new version with a migration in [migrations].
@Database(
    entities = [
        TitleRow::class,
        MangaRow::class,
        MangaListStateRow::class,
        MangaListEntryRow::class,
        TitleDetailsRow::class,
        TitleRelationRow::class,
        TitleRecommendationRow::class,
        ListEntryRow::class,
        ListStateRow::class,
        AiringRow::class,
        AiringEpisodeRow::class,
        ProfileRow::class,
        NotificationStateRow::class,
        NotificationTitleRow::class,
        SentRow::class,
    ],
    version = KirokuDatabase.VERSION,
)
abstract class KirokuDatabase : RoomDatabase() {
    internal abstract fun titles(): TitleDao

    internal abstract fun manga(): MangaDao

    internal abstract fun mangaList(): MangaListDao

    internal abstract fun list(): ListDao

    internal abstract fun airing(): AiringDao

    internal abstract fun profile(): ProfileDao

    internal abstract fun notifications(): NotificationDao

    companion object {
        const val NAME = "kiroku.db"
        const val VERSION = 11

        val migrations: List<Migration> =
            listOf(
                // Airing data, in tables of its own.
                migration(1, 2) {
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `airing` (`title_id` INTEGER NOT NULL, `fetched_at` INTEGER NOT NULL, " +
                            "`known` INTEGER NOT NULL, `total_episodes` INTEGER, PRIMARY KEY(`title_id`))",
                    )
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `airing_episode` (`title_id` INTEGER NOT NULL, `number` INTEGER NOT NULL, " +
                            "`airs_at` INTEGER NOT NULL, PRIMARY KEY(`title_id`, `number`), FOREIGN KEY(`title_id`) " +
                            "REFERENCES `airing`(`title_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                },
                // A stored schedule whose numbering the source doubts; none was before.
                migration(2, 3) {
                    execSQL("ALTER TABLE `airing` ADD COLUMN `doubted_over` INTEGER")
                },
                // The signed-in user's profile, in a table of its own.
                migration(3, 4) {
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `own_profile` (`owner` TEXT NOT NULL, `name` TEXT NOT NULL, `picture` TEXT, " +
                            "`joined` TEXT, `fetched_at` INTEGER NOT NULL, `stated_watching` INTEGER, `stated_completed` INTEGER, " +
                            "`stated_on_hold` INTEGER, `stated_dropped` INTEGER, `stated_planned` INTEGER, `stated_entries` INTEGER, " +
                            "`stated_episodes` INTEGER, `stated_rewatched` INTEGER, `stated_days_watched` REAL, " +
                            "`stated_mean_score` REAL, PRIMARY KEY(`owner`))",
                    )
                },
                // The record of notifications and what was posted, in tables of their own and empty (N10).
                migration(4, 5) {
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `notification_state` (`id` INTEGER NOT NULL, `owner` TEXT, `since` INTEGER, " +
                            "`planned_since` INTEGER, PRIMARY KEY(`id`))",
                    )
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `notification_title` (`title_id` INTEGER NOT NULL, `followed_status` TEXT, " +
                            "`followed_since` INTEGER, `announced_episode` INTEGER, `announced_aired_at` INTEGER, " +
                            "`taken_back_to` INTEGER, `standing` INTEGER NOT NULL, PRIMARY KEY(`title_id`))",
                    )
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `notification_sent` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`title_id` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `aired_at` INTEGER NOT NULL, " +
                            "`notify_at` INTEGER NOT NULL, `posted_at` INTEGER NOT NULL, `recheck` TEXT NOT NULL, `shown` INTEGER NOT NULL)",
                    )
                },
                // An entry's rewatch count, 0 for a stored entry until the next download.
                migration(5, 6) {
                    for (record in listOf("shown", "confirmed")) {
                        execSQL("ALTER TABLE `list_entry` ADD COLUMN `${record}_times_rewatched` INTEGER")
                        execSQL("UPDATE `list_entry` SET `${record}_times_rewatched` = 0 WHERE `${record}_status` IS NOT NULL")
                    }
                },
                migration(6, 7) {
                    execSQL("ALTER TABLE `title_details` ADD COLUMN `recommendations_include_adult` INTEGER DEFAULT 0")
                },
                migration(7, 8) {
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `manga` (`id` INTEGER NOT NULL, `romaji` TEXT NOT NULL, " +
                            "`english` TEXT, `japanese` TEXT, `poster` TEXT, `large_poster` TEXT, `synopsis` TEXT, " +
                            "`format` TEXT, `publication` TEXT, `volumes` INTEGER, `chapters` INTEGER, " +
                            "`mean_score` REAL, `fetched_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                },
                migration(8, 9) {
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `manga_list_state` (`owner` TEXT NOT NULL, " +
                            "`synced_at` INTEGER NOT NULL, PRIMARY KEY(`owner`))",
                    )
                    execSQL(
                        "CREATE TABLE IF NOT EXISTS `manga_list_entry` (`owner` TEXT NOT NULL, `manga_id` INTEGER NOT NULL, " +
                            "`romaji` TEXT NOT NULL, `english` TEXT, `japanese` TEXT, `synonyms` TEXT NOT NULL, `poster` TEXT, " +
                            "`status` TEXT NOT NULL, `chapters_read` INTEGER, `volumes_read` INTEGER, `score` INTEGER, " +
                            "`updated_at` INTEGER, PRIMARY KEY(`owner`, `manga_id`), FOREIGN KEY(`owner`) " +
                            "REFERENCES `manga_list_state`(`owner`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                },
                migration(9, 10) {
                    execSQL("ALTER TABLE `manga_list_state` ADD COLUMN `needs_refresh` INTEGER NOT NULL DEFAULT 0")
                    execSQL("ALTER TABLE `manga_list_entry` ADD COLUMN `unverified_numbers` INTEGER NOT NULL DEFAULT 7")
                },
                migration(10, 11) {
                    execSQL("ALTER TABLE `manga` ADD COLUMN `rank` INTEGER")
                    execSQL("ALTER TABLE `manga` ADD COLUMN `members` INTEGER")
                },
            )

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

private fun migration(
    from: Int,
    to: Int,
    steps: SQLiteConnection.() -> Unit,
): Migration =
    object : Migration(from, to) {
        override fun migrate(connection: SQLiteConnection) = connection.steps()
    }

// One transaction around the calls made in [block]: all of them or none.
internal suspend fun <T> KirokuDatabase.transaction(block: suspend () -> T): T = useWriterConnection { it.immediateTransaction { block() } }

// SQLite before 3.32 takes 999 variables in a statement.
internal const val MAX_VARIABLES = 900

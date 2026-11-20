package io.github.kauri_off.kiroku.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.DatabaseConfiguration
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.ListRecord
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.StatedFigures
import io.github.kauri_off.kiroku.core.model.UserProfile
import io.github.kauri_off.kiroku.core.notify.Announced
import io.github.kauri_off.kiroku.core.notify.Followed
import io.github.kauri_off.kiroku.core.notify.NotificationRecord
import io.github.kauri_off.kiroku.core.notify.Recheck
import io.github.kauri_off.kiroku.core.notify.SentNotification
import io.github.kauri_off.kiroku.core.notify.StoredNotifications
import io.github.kauri_off.kiroku.core.notify.TitleRecord
import io.github.kauri_off.kiroku.data.preferences.StoredPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.Instant

// The harness of section 7.1: every schema change is a migration that is run here, and nothing erases the database.
@RunWith(RobolectricTestRunner::class)
class KirokuDatabaseTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration.db"
    private val anime = FixtureAnime.exactToWatch
    private val details = Fixtures.reference.details.getValue(anime.id)
    private val fetchedAt = Instant.parse("2026-01-11T21:40:00.123456789Z")

    private val airing =
        mapOf(
            AiringRow.TABLE to
                listOf(
                    mapOf("title_id" to 7L, "fetched_at" to fetchedAt.toColumn(), "known" to 0L, "total_episodes" to null),
                    mapOf("title_id" to anime.id.value, "fetched_at" to fetchedAt.toColumn(), "known" to 1L, "total_episodes" to 24L),
                    // A schedule in doubt, from version 3 on; an earlier version has no column for it.
                    mapOf(
                        "title_id" to 8L,
                        "fetched_at" to fetchedAt.toColumn(),
                        "known" to 1L,
                        "total_episodes" to 12L,
                        "doubted_over" to 13L,
                    ),
                ),
            AiringEpisodeRow.TABLE to
                listOf(1L, 2L).map {
                    mapOf("title_id" to anime.id.value, "number" to it, "airs_at" to fetchedAt.plusSeconds(it).toColumn())
                },
        )

    private val profile =
        mapOf(
            ProfileRow.TABLE to
                listOf(
                    mapOf(
                        "owner" to "owner",
                        "name" to "someone",
                        "joined" to "2020-03-04",
                        "fetched_at" to fetchedAt.toColumn(),
                        "stated_watching" to 3L,
                        "stated_mean_score" to 7.5,
                    ),
                ),
        )

    // The record of a signed-in list: a title announced and followed, one announced and no longer followed, one followed only.
    private val record =
        mapOf(
            NotificationStateRow.TABLE to
                listOf(mapOf("id" to 0L, "owner" to "owner", "since" to fetchedAt.toColumn(), "planned_since" to null)),
            NotificationTitleRow.TABLE to
                listOf(
                    mapOf(
                        "title_id" to anime.id.value,
                        "followed_status" to "watching",
                        "followed_since" to fetchedAt.toColumn(),
                        "announced_episode" to 3L,
                        "announced_aired_at" to fetchedAt.plusSeconds(3).toColumn(),
                        "taken_back_to" to 2L,
                        "standing" to 1L,
                    ),
                    mapOf(
                        "title_id" to 7L,
                        "announced_episode" to 12L,
                        "announced_aired_at" to fetchedAt.plusSeconds(12).toColumn(),
                        "standing" to 0L,
                    ),
                    mapOf(
                        "title_id" to 8L,
                        "followed_status" to "plan_to_watch",
                        "followed_since" to fetchedAt.toColumn(),
                        "standing" to 0L,
                    ),
                ),
            SentRow.TABLE to
                listOf(3L, 12L).mapIndexed { index, episode ->
                    mapOf(
                        "id" to index + 1L,
                        "title_id" to if (episode == 3L) anime.id.value else 7L,
                        "episode" to episode,
                        "aired_at" to fetchedAt.plusSeconds(episode).toColumn(),
                        "notify_at" to fetchedAt.plusSeconds(episode).toColumn(),
                        "posted_at" to fetchedAt.plusSeconds(episode + 60).toColumn(),
                        "recheck" to "answered",
                        "shown" to index.toLong(),
                    )
                },
        )

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            context.getDatabasePath(name),
            AndroidSQLiteDriver(),
            KirokuDatabase::class,
        )

    private fun <T> KirokuDatabase.use(block: suspend (TitleStore) -> T): T =
        try {
            runBlocking { block(TitleStore(this@use, Dispatchers.IO) { throw it }) }
        } finally {
            close()
        }

    private fun stored(name: String): Anime? = KirokuDatabase.open(context, name).use { it.observeAnime(anime.id).first() }

    @Serializable
    private class Exported(
        val database: Database,
    ) {
        @Serializable
        class Database(
            val version: Int,
            val identityHash: String,
        )
    }

    private fun exportedIdentity(version: Int): String {
        val schema = "${KirokuDatabase::class.java.canonicalName}/$version.json"
        val text = context.assets.open(schema).use { it.reader().readText() }
        val database = json.decodeFromString<Exported>(text).database
        assertEquals(schema, version, database.version)
        return database.identityHash
    }

    @Test
    fun `every version up to the current one has its exported schema, with the identity it was exported with`() {
        assertEquals((1..KirokuDatabase.VERSION).toList(), IDENTITIES.keys.toList())
        for ((version, identity) in IDENTITIES) assertEquals("$version.json", identity, exportedIdentity(version))
    }

    @Test
    fun `the database the app opens has the current version's identity`() {
        KirokuDatabase.open(context, name).use { it.observeAnime(anime.id).first() }
        val opened =
            AndroidSQLiteDriver().open(context.getDatabasePath(name).path).use { connection ->
                connection.prepare("SELECT identity_hash FROM room_master_table").use {
                    it.step()
                    it.getText(0)
                }
            }
        assertEquals(IDENTITIES.getValue(KirokuDatabase.VERSION), opened)
    }

    @Test
    fun `the migrations are the steps from each version to the next`() {
        assertEquals(
            (1 until KirokuDatabase.VERSION).map { it to it + 1 },
            KirokuDatabase.migrations.map { it.startVersion to it.endVersion },
        )
    }

    @Test
    fun `the app opens a database made from the current version's exported schema and uses it`() {
        helper.createDatabase(KirokuDatabase.VERSION).close()

        val read =
            KirokuDatabase.open(context, name).use {
                it.refresh(anime, details, Fixtures.reference.now)
                it.observeDetails(anime.id).first()
            }

        assertEquals(StoredDetails(details, Fixtures.reference.now), read)
        assertEquals(anime, stored(name))
    }

    @Test
    fun `a database of every earlier version becomes the current one by the app's migrations`() {
        for (version in 1 until KirokuDatabase.VERSION) {
            context.deleteDatabase(name)
            helper.createDatabase(version).close()
            helper.runMigrationsAndValidate(KirokuDatabase.VERSION, KirokuDatabase.migrations).close()
        }
    }

    // The app's version 1 wrote it: the fixtures' list and titles, entries not yet confirmed and a status only MAL knows.
    private fun writtenByVersionOne(name: String): File {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        checkNotNull(javaClass.getResourceAsStream("/databases/kiroku-1.db")).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun SQLiteConnection.tables(): List<String> =
        prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use {
            buildList { while (it.step()) add(it.getText(0)) }
        } - setOf("android_metadata", "room_master_table", "sqlite_sequence")

    // Each value as SQLite holds it: a long, a double, a text, bytes or null.
    private fun SQLiteConnection.rows(table: String): List<List<Any?>> =
        prepare("SELECT * FROM $table ORDER BY rowid").use { row ->
            buildList {
                while (row.step()) {
                    add(
                        (0 until row.getColumnCount()).map {
                            when (row.getColumnType(it)) {
                                SQLITE_DATA_INTEGER -> row.getLong(it)
                                SQLITE_DATA_FLOAT -> row.getDouble(it)
                                SQLITE_DATA_TEXT -> row.getText(it)
                                SQLITE_DATA_BLOB -> row.getBlob(it).toList()
                                else -> null
                            }
                        },
                    )
                }
            }
        }

    private fun SQLiteConnection.insert(
        table: String,
        rows: List<List<Any?>>,
    ) {
        for (values in rows) {
            prepare("INSERT INTO $table VALUES (${values.joinToString { "?" }})").use { insert ->
                values.forEachIndexed { index, value ->
                    when (value) {
                        is Long -> insert.bindLong(index + 1, value)
                        is Double -> insert.bindDouble(index + 1, value)
                        is String -> insert.bindText(index + 1, value)
                        is List<*> -> insert.bindBlob(index + 1, value.map { it as Byte }.toByteArray())
                        else -> insert.bindNull(index + 1)
                    }
                }
                insert.step()
            }
        }
    }

    private fun rowsOf(file: File): Map<String, List<List<Any?>>> =
        AndroidSQLiteDriver().open(file.path).use { connection -> connection.tables().associateWith { connection.rows(it) } }

    private fun List<List<Any?>>.ids(): List<AnimeId> = map { AnimeId(it.first() as Long) }

    // A row as each column of its table holds it.
    private fun SQLiteConnection.named(table: String): List<Map<String, Any?>> =
        prepare("SELECT * FROM $table LIMIT 0").use { columns ->
            val names = (0 until columns.getColumnCount()).map { columns.getColumnName(it) }
            rows(table).map { names.zip(it).toMap() }
        }

    // Each row with those of its values whose column the table has.
    private fun SQLiteConnection.insertNamed(
        table: String,
        rows: List<Map<String, Any?>>,
    ) {
        val columns =
            prepare("SELECT * FROM $table LIMIT 0").use { query ->
                (0 until query.getColumnCount()).map { query.getColumnName(it) }
            }
        val held = rows.map { row -> columns.filter { it in row } }
        for ((row, names) in rows.zip(held)) {
            prepare("INSERT INTO $table (${names.joinToString()}) VALUES (${names.joinToString { "?" }})").use { insert ->
                names.forEachIndexed { index, column ->
                    when (val value = row[column]) {
                        is Long -> insert.bindLong(index + 1, value)
                        is Double -> insert.bindDouble(index + 1, value)
                        is String -> insert.bindText(index + 1, value)
                        is List<*> -> insert.bindBlob(index + 1, value.map { it as Byte }.toByteArray())
                        else -> insert.bindNull(index + 1)
                    }
                }
                insert.step()
            }
        }
    }

    // N10, section 7.1: a database of each version, every table of it holding rows, the record's announced episodes among them,
    // is each row as it was in the current version, and a table it did not have is empty there.
    @Test
    fun `a database of each version keeps every row of its list, titles, airing data, profile and record through the migrations`() {
        val list =
            AndroidSQLiteDriver().open(writtenByVersionOne("written-1.db").path).use { connection ->
                connection.tables().associateWith { connection.named(it) }
            }
        assertTrue(list.keys.containsAll(TABLES))
        assertTrue(list.values.all { it.isNotEmpty() })
        val fixture = list + airing + profile + record
        for (version in 1..KirokuDatabase.VERSION) {
            context.deleteDatabase(name)
            val written =
                helper.createDatabase(version).use { connection ->
                    val tables = connection.tables()
                    for ((table, rows) in fixture.entries.sortedBy { it.key != TitleRow.TABLE && it.key != AiringRow.TABLE }) {
                        if (table in tables) connection.insertNamed(table, rows)
                    }
                    tables.associateWith { connection.named(it) }
                }
            assertEquals("$version: every table holds rows", written.keys, written.filterValues { it.isNotEmpty() }.keys)
            if (NotificationTitleRow.TABLE in written) {
                assertTrue(
                    "$version: announced episodes",
                    written.getValue(NotificationTitleRow.TABLE).count { it["announced_episode"] != null } >= 2,
                )
            }

            helper.runMigrationsAndValidate(KirokuDatabase.VERSION, KirokuDatabase.migrations).use { connection ->
                for ((table, rows) in written) {
                    val kept = connection.named(table).map { row -> row.filterKeys { it in rows.first() } }
                    assertEquals("$version: $table", rows, kept)
                }
                for (table in connection.tables() - written.keys) assertEquals("$version: $table", 0, connection.rows(table).size)
            }
        }
    }

    @Test
    fun `the app opens a version 1 database it wrote, keeps every row and reads its list and titles from it`() =
        runBlocking {
            val written = writtenByVersionOne("opened-1.db")
            val before = rowsOf(written)
            val database = KirokuDatabase.open(context, written.name)
            val unexpected = mutableListOf<Throwable>()
            val scope = CoroutineScope(Dispatchers.IO)
            val list = RoomLocalList(database, scope, Dispatchers.IO) { unexpected += it }
            val airing = RoomAiringStore(database, Dispatchers.IO) { unexpected += it }
            database.use { titles ->
                assertEquals(SignInId("owner"), list.state().owner)
                assertNotNull(list.state().syncedAt)
                val entries = list.readAll()
                assertEquals(before.getValue("list_entry").size, entries.size)
                assertEquals(entries.size - 1, list.observeEntries().first().size)
                val edited = entries.getValue(FixtureAnime.exactToWatch.id)
                assertEquals(
                    listOf(true, 13, 12),
                    listOf(edited.unconfirmed, edited.shown?.values?.watchedEpisodes, edited.confirmed?.values?.watchedEpisodes),
                )
                assertEquals(null, entries.getValue(FixtureAnime.film.id).shown)
                assertEquals(
                    Fixtures.unknownListStatus,
                    entries
                        .getValue(FixtureAnime.completedOna.id)
                        .shown
                        ?.values
                        ?.status,
                )
                for (id in before.getValue("title").ids()) assertNotNull(titles.observeAnime(id).first())
                for (id in before.getValue("title_details").ids()) assertNotNull(titles.observeDetails(id).first())

                assertEquals(emptyMap<AnimeId, StoredAiring>(), airing.read())
                val stored = Fixtures.reference.schedules.mapValues { StoredAiring(it.value, Fixtures.reference.now) }
                airing.write(stored)
                assertEquals(stored, airing.read())

                val profiles = RoomProfileStore(database, Dispatchers.IO) { unexpected += it }
                assertEquals(null, profiles.observe().first())
                val stated = StatedFigures(1, null, 0, 0, 2, 3, 40, null, 0.5, null)
                val profile = StoredProfile(SignInId("owner"), UserProfile("someone", null, null, stated), fetchedAt)
                assertTrue(profiles.replace(profile) { true })
                assertEquals(profile, profiles.observe().first())

                val preferences = StoredPreferences(File(context.filesDir, "opened-1.preferences_pb"), scope) { unexpected += it }
                val notifications = RoomNotificationStore(database, preferences, Dispatchers.IO) { unexpected += it }
                assertEquals(StoredNotifications(null, NotificationRecord()), notifications.read())
                assertEquals(emptyList<SentNotification>(), notifications.observeSent().first())
                val announced =
                    NotificationRecord(
                        since = fetchedAt,
                        titles =
                            mapOf(
                                anime.id to TitleRecord(Followed(ListStatus.Watching, fetchedAt), Announced(3, fetchedAt), standing = true),
                            ),
                    )
                val sent =
                    SentNotification(
                        checkNotNull(titles.observeAnime(anime.id).first()),
                        3,
                        fetchedAt,
                        fetchedAt,
                        fetchedAt.plusSeconds(1),
                        Recheck.Answered,
                    )
                notifications.clear(SignInId("owner"))
                notifications.write(announced, listOf(sent), fetchedAt)
                assertEquals(StoredNotifications(SignInId("owner"), announced), notifications.read())
                assertEquals(listOf(sent), notifications.observeSent().first())
            }
            scope.cancel()

            assertEquals(emptyList<Throwable>(), unexpected)
            val after = rowsOf(written)
            for ((table, rows) in before) assertEquals(table, rows, after.getValue(table).map { it.take(rows.first().size) })
        }

    // The app's version 5 wrote it: version 1's list opened by version 5, with airing data, a profile and announced episodes.
    @Test
    fun `a version 5 database the app wrote keeps every row, counts no rewatch until MAL gives one and keeps a count stored`() {
        val written = context.getDatabasePath("opened-5.db")
        checkNotNull(javaClass.getResourceAsStream("/databases/kiroku-5.db")).use { input ->
            written.outputStream().use { input.copyTo(it) }
        }
        // Beside its rows, a title added on the device that MAL never confirmed: a shown record and no confirmed one.
        AndroidSQLiteDriver().open(written.path).use { connection ->
            val title =
                connection.prepare("SELECT id FROM title WHERE id NOT IN (SELECT title_id FROM list_entry) LIMIT 1").use {
                    check(it.step())
                    it.getLong(0)
                }
            val added =
                mapOf(
                    "title_id" to title,
                    "unconfirmed" to 1L,
                    "shown_status" to "plan_to_watch",
                    "shown_watched" to 0L,
                    "shown_updated_at" to fetchedAt.toColumn(),
                )
            connection.insertNamed(ListEntryRow.TABLE, listOf(added))
        }
        val before = rowsOf(written)
        assertTrue(before.getValue(ListEntryRow.TABLE).any { it[1] == 1L && it[8] == null && it[2] != null })
        val id = FixtureAnime.exactToWatch.id

        val stored = listIn(written.name) { it.readAll() }

        assertEquals(before.getValue(ListEntryRow.TABLE).size, stored.size)
        val records = stored.values.flatMap { listOfNotNull(it.shown, it.confirmed) }
        assertEquals(setOf(0), records.map { it.values.timesRewatched }.toSet())
        val after = rowsOf(written)
        for ((table, rows) in before) assertEquals(table, rows, after.getValue(table).map { it.take(rows.firstOrNull()?.size ?: 0) })
        val entries = AndroidSQLiteDriver().open(written.path).use { it.named(ListEntryRow.TABLE) }
        for (record in listOf("shown", "confirmed")) {
            assertEquals(
                record,
                entries.map { if (it["${record}_status"] == null) null else 0L },
                entries.map { it["${record}_times_rewatched"] },
            )
        }

        val edited = stored.getValue(id)
        val rewatched = StoredEntry(edited.shown?.rewatched(2), edited.confirmed?.rewatched(1), unconfirmed = true)
        val read =
            listIn(written.name) {
                it.write(mapOf(id to rewatched))
                it.readAll().getValue(id) to
                    it
                        .observeEntry(id)
                        .first()
                        ?.values
                        ?.timesRewatched
            }
        assertEquals(rewatched to 2, read)
    }

    private fun ListRecord.rewatched(times: Int) = copy(values = values.copy(timesRewatched = times))

    private fun <T> listIn(
        name: String,
        block: suspend (RoomLocalList) -> T,
    ): T {
        val database = KirokuDatabase.open(context, name)
        val scope = CoroutineScope(Dispatchers.IO)
        return try {
            runBlocking { block(RoomLocalList(database, scope, Dispatchers.IO) { throw it }) }
        } finally {
            scope.cancel()
            database.close()
        }
    }

    @Test
    fun `a version 2 database keeps its airing data through the migrations, with no schedule in doubt`() {
        val known = listOf(anime.id.value, fetchedAt.toColumn(), 1L, 24L)
        val episodes = listOf(1, 2).map { listOf(anime.id.value, it.toLong(), fetchedAt.plusSeconds(it.toLong()).toColumn()) }
        helper.createDatabase(2).use { connection ->
            connection.insert(AiringRow.TABLE, listOf(listOf(7L, fetchedAt.toColumn(), 0L, null), known))
            connection.insert(AiringEpisodeRow.TABLE, episodes)
        }

        helper.runMigrationsAndValidate(KirokuDatabase.VERSION, KirokuDatabase.migrations).use { connection ->
            assertEquals(listOf(listOf(7L, fetchedAt.toColumn(), 0L, null, null), known + null), connection.rows(AiringRow.TABLE))
            assertEquals(episodes, connection.rows(AiringEpisodeRow.TABLE))
        }
        val database = KirokuDatabase.open(context, name)
        val read = database.use { _ -> RoomAiringStore(database, Dispatchers.IO) { error -> throw error }.read() }

        val schedule = AiringSchedule(listOf(1, 2).map { ScheduledEpisode(it, fetchedAt.plusSeconds(it.toLong())) }, 24)
        assertEquals(mapOf(anime.id to StoredAiring(schedule, fetchedAt), AnimeId(7) to StoredAiring(null, fetchedAt)), read)
    }

    @Test
    fun `a title that has an entry or that a page names cannot be deleted, and a deleted title takes its details along`() {
        val database = KirokuDatabase.open(context, name)
        val list = RoomLocalList(database, CoroutineScope(Dispatchers.IO), Dispatchers.IO) { throw it }
        val entry = checkNotNull(Fixtures.reference.entryFor(anime.id))
        val delete = "DELETE FROM title WHERE id = ${anime.id.value}"
        database.use { titles ->
            titles.refresh(anime, details, Fixtures.reference.now)
            list.add(anime, StoredEntry.settled(entry.record))

            val named =
                details.related
                    .first()
                    .anime.id.value
            assertTrue(runCatching { database.execute(delete) }.isFailure)
            assertTrue(runCatching { database.execute("DELETE FROM title WHERE id = $named") }.isFailure)
            assertTrue(
                runCatching { database.execute("DELETE FROM title WHERE id = ${details.recommendations.last().id.value}") }.isFailure,
            )
            assertTrue(runCatching { database.execute("INSERT INTO list_entry (title_id, unconfirmed) VALUES (1, 1)") }.isFailure)
            assertEquals(StoredTitle(anime, StoredEntry.settled(entry.record)), list.read(anime.id))

            list.write(mapOf(anime.id to null))
            database.execute(delete)
            assertEquals(null, titles.observeAnime(anime.id).first())
            assertEquals(null, titles.observeDetails(anime.id).first())
            assertEquals(
                null,
                titles
                    .observeDetails(
                        details.related
                            .first()
                            .anime.id,
                    ).first(),
            )
            assertNotNull(
                titles
                    .observeAnime(
                        details.related
                            .first()
                            .anime.id,
                    ).first(),
            )
        }
    }

    @Test
    fun `no version may be reached by erasing the database`() {
        val database = KirokuDatabase.open(context, "configured.db")
        val field = RoomDatabase::class.java.getDeclaredField("configuration").apply { isAccessible = true }
        val configuration = field.get(database) as DatabaseConfiguration
        database.close()

        val versions = 1..KirokuDatabase.VERSION + 3
        for (from in versions) for (to in versions) if (from != to) assertTrue("$from to $to", configuration.isMigrationRequired(from, to))
        assertTrue(configuration.requireMigration)
    }

    @Test
    fun `a database a newer version of the app wrote is refused and keeps what it holds`() {
        KirokuDatabase.open(context, name).use { it.refresh(anime, details, Fixtures.reference.now) }
        assertEquals(anime, stored(name))
        val path = context.getDatabasePath(name).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = KirokuDatabase.VERSION + 1 }

        val refused = runCatching { stored(name) }.exceptionOrNull()

        assertNotNull(refused)
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(KirokuDatabase.VERSION + 1, raw.version)
            raw.rawQuery("SELECT romaji FROM title WHERE id = ?", arrayOf(anime.id.value.toString())).use {
                assertTrue(it.moveToFirst())
                assertEquals(anime.titles.romaji, it.getString(0))
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        // Every version that may be on a device, as exported: a changed entity is a new version, never a new identity here.
        val IDENTITIES =
            mapOf(
                1 to "c908fc01889fca24c50eae1d46661170",
                2 to "569924a95101271cd2502ea512c63260",
                3 to "bf46dac8cbf593d04a1acac52b0f3c99",
                4 to "7e5f8a37c34960ccc7f6550ea8aae3f2",
                5 to "2ec16b4ab274d6bfa0ed4f5d94531644",
                6 to "0225027f282ee667b6e073930642ad78",
            )
    }
}

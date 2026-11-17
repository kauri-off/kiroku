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
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.UserProfile
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
                        else -> insert.bindNull(index + 1)
                    }
                }
                insert.step()
            }
        }
    }

    // N10, section 7.1: a list, its titles and airing data, in a database of each earlier version, are each row as they were.
    @Test
    fun `a database of each earlier version keeps every row of its list, titles and airing data through the migrations`() {
        val list =
            AndroidSQLiteDriver().open(writtenByVersionOne("written-1.db").path).use { connection ->
                connection.tables().associateWith { connection.named(it) }
            }
        val airing =
            mapOf(
                AiringRow.TABLE to
                    listOf(
                        mapOf("title_id" to 7L, "fetched_at" to fetchedAt.toColumn(), "known" to 0L, "total_episodes" to null),
                        mapOf("title_id" to anime.id.value, "fetched_at" to fetchedAt.toColumn(), "known" to 1L, "total_episodes" to 24L),
                    ),
                AiringEpisodeRow.TABLE to
                    listOf(1L, 2L).map {
                        mapOf("title_id" to anime.id.value, "number" to it, "airs_at" to fetchedAt.plusSeconds(it).toColumn())
                    },
            )
        assertTrue(list.keys.containsAll(TABLES))
        assertTrue(list.values.all { it.isNotEmpty() })
        for (version in 1 until KirokuDatabase.VERSION) {
            context.deleteDatabase(name)
            val written =
                helper.createDatabase(version).use { connection ->
                    val tables = connection.tables()
                    for ((table, rows) in (list + airing).entries.sortedBy { it.key != TitleRow.TABLE && it.key != AiringRow.TABLE }) {
                        if (table in tables) connection.insertNamed(table, rows)
                    }
                    tables.associateWith { connection.named(it) }
                }
            assertEquals(
                "$version",
                list.keys +
                    if (version >=
                        2
                    ) {
                        airing.keys
                    } else {
                        emptySet()
                    },
                written.filterValues { it.isNotEmpty() }.keys,
            )

            helper.runMigrationsAndValidate(KirokuDatabase.VERSION, KirokuDatabase.migrations).use { connection ->
                for ((table, rows) in written) {
                    val kept = connection.named(table).map { row -> row.filterKeys { it in rows.firstOrNull().orEmpty() } }
                    assertEquals("$version: $table", rows, kept)
                }
                assertEquals("$version", 0, connection.rows(ProfileRow.TABLE).size)
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
                val profile = StoredProfile(SignInId("owner"), UserProfile("someone", null, null), fetchedAt)
                assertTrue(profiles.replace(profile) { true })
                assertEquals(profile, profiles.observe().first())
            }
            scope.cancel()

            assertEquals(emptyList<Throwable>(), unexpected)
            val after = rowsOf(written)
            for ((table, rows) in before) assertEquals(table, rows, after.getValue(table))
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
            )
    }
}

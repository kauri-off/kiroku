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
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.Anime
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

// The harness of section 7.1: every schema change is a migration that is run here, and nothing erases the database.
@RunWith(RobolectricTestRunner::class)
class KirokuDatabaseTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration.db"
    private val anime = FixtureAnime.exactToWatch
    private val details = Fixtures.reference.details.getValue(anime.id)

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

    @Test
    fun `every version has its exported schema, and the migrations are the steps from each to the next`() {
        for (version in 1..KirokuDatabase.VERSION) {
            val schema = "${KirokuDatabase::class.java.canonicalName}/$version.json"
            assertTrue(schema, context.assets.open(schema).use { it.read() } >= 0)
        }
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

    @Test
    fun `a version 1 database on its exported schema keeps every row of its list and titles through the migrations`() {
        val written = rowsOf(writtenByVersionOne("written-1.db"))
        assertTrue(written.keys.containsAll(TABLES))
        assertTrue(written.values.all { it.isNotEmpty() })
        helper.createDatabase(1).use { connection ->
            for ((table, rows) in written.entries.sortedBy { it.key != TitleRow.TABLE }) connection.insert(table, rows)
        }

        helper.runMigrationsAndValidate(KirokuDatabase.VERSION, KirokuDatabase.migrations).use { connection ->
            for ((table, rows) in written) assertEquals(table, rows, connection.rows(table))
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
            }
            scope.cancel()

            assertEquals(emptyList<Throwable>(), unexpected)
            val after = rowsOf(written)
            for ((table, rows) in before) assertEquals(table, rows, after.getValue(table))
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
}

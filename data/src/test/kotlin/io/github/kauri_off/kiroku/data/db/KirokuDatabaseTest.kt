package io.github.kauri_off.kiroku.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.DatabaseConfiguration
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.core.list.StoredEntry
import io.github.kauri_off.kiroku.core.list.StoredTitle
import io.github.kauri_off.kiroku.core.list.record
import io.github.kauri_off.kiroku.core.model.Anime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

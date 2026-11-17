package io.github.kauri_off.kiroku.data.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kauri_off.kiroku.core.airing.AiringSchedule
import io.github.kauri_off.kiroku.core.airing.ScheduledEpisode
import io.github.kauri_off.kiroku.core.airing.StoredAiring
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

// Section 7.1 on the device's own SQLite: the database the app's version 1 wrote becomes the current one, keeps every row, and stores a profile.
class MigrationOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "migrated-1.db"

    @After
    fun delete() {
        context.deleteDatabase(name)
    }

    private fun written(): File {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        instrumentation.context.assets
            .open("kiroku-1.db")
            .use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun <T> raw(
        file: File,
        read: (SQLiteDatabase) -> T,
    ): T = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(read)

    private fun SQLiteDatabase.tables(): List<String> =
        rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        } - setOf("android_metadata", "room_master_table", "sqlite_sequence")

    // Each value as SQLite holds it: a long, a double, a text, bytes or null.
    private fun SQLiteDatabase.rows(table: String): List<List<Any?>> =
        rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).map {
                            when (cursor.getType(it)) {
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(it)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(it)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(it)
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(it).toList()
                                else -> null
                            }
                        },
                    )
                }
            }
        }

    @Test
    fun the_app_opens_a_version_1_database_it_wrote_and_keeps_every_row() {
        val file = written()
        val before =
            raw(file) { database ->
                assertEquals(1, database.version)
                database.rawQuery("SELECT sqlite_version()", null).use {
                    it.moveToFirst()
                    Log.i(TAG, "SQLite ${it.getString(0)}")
                }
                database.tables().associateWith { database.rows(it) }
            }
        assertTrue(
            before.keys.containsAll(listOf("title", "title_details", "title_relation", "title_recommendation", "list_entry", "list_state")),
        )
        assertTrue(before.values.all { it.isNotEmpty() })

        val database = KirokuDatabase.open(context, name)
        val scope = CoroutineScope(Dispatchers.IO)
        val unexpected = mutableListOf<Throwable>()
        try {
            runBlocking {
                val list = RoomLocalList(database, scope, Dispatchers.IO) { unexpected += it }
                assertEquals(before.getValue("list_entry").size, list.readAll().size)
                val airing = RoomAiringStore(database, Dispatchers.IO) { unexpected += it }
                assertEquals(emptyMap<AnimeId, StoredAiring>(), airing.read())
                val stored =
                    mapOf(
                        AnimeId(1) to
                            StoredAiring(
                                AiringSchedule(listOf(ScheduledEpisode(1, Instant.parse("2026-01-17T14:30:00.000000001Z"))), 12),
                                Instant.EPOCH,
                            ),
                        AnimeId(2) to StoredAiring(null, Instant.EPOCH),
                    )
                airing.write(stored)
                assertEquals(stored, airing.read())
                val profiles = RoomProfileStore(database, Dispatchers.IO) { unexpected += it }
                assertEquals(null, profiles.observe().first())
                val profile = StoredProfile(SignInId("owner"), UserProfile("someone", null, null), Instant.EPOCH)
                assertTrue(profiles.replace(profile) { true })
                assertEquals(profile, profiles.observe().first())
            }
        } finally {
            scope.cancel()
            database.close()
        }
        assertEquals(emptyList<Throwable>(), unexpected)

        raw(file) { migrated ->
            assertEquals(KirokuDatabase.VERSION, migrated.version)
            for ((table, rows) in before) assertEquals(table, rows, migrated.rows(table))
        }
        Log.i(TAG, "Every row of ${before.size} tables kept: ${before.mapValues { it.value.size }}")
    }

    private companion object {
        const val TAG = "KirokuMigration"
    }
}

package io.github.kauri_off.kiroku.data.db

import androidx.room.RoomDatabase
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import kotlin.coroutines.ContinuationInterceptor

// Databases in memory that a test's scheduler drives, closed when the test ends.
class TestDatabases : ExternalResource() {
    private val opened = mutableListOf<KirokuDatabase>()

    // Every statement Room ran, once for each time it ran, of any database opened here.
    val statements = mutableListOf<String>()

    fun open(
        scope: TestScope,
        dispatcher: TestDispatcher = scope.dispatcher,
    ): KirokuDatabase =
        KirokuDatabase
            .builder(RuntimeEnvironment.getApplication(), name = null)
            .setDriver(Recording(AndroidSQLiteDriver()))
            .setQueryCoroutineContext(dispatcher)
            .build()
            .also { opened += it }

    override fun after() = opened.forEach { it.close() }

    private inner class Recording(
        private val driver: SQLiteDriver,
    ) : SQLiteDriver {
        override fun open(fileName: String): SQLiteConnection {
            val connection = driver.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        private var running = false

                        override fun step(): Boolean {
                            if (!running) statements += sql
                            running = true
                            return statement.step()
                        }

                        override fun reset() {
                            running = false
                            statement.reset()
                        }
                    }
                }
            }
        }
    }
}

// The dispatcher the test itself runs on: standard, or unconfined when the test asks for that.
internal val TestScope.dispatcher: TestDispatcher get() = coroutineContext[ContinuationInterceptor] as TestDispatcher

internal const val LISTED_QUERY = "FROM list_entry e JOIN title t"

// The tables each later write changes a row of, as Room tells its queries.
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.written(database: RoomDatabase): List<Set<String>> {
    val seen = mutableListOf<Set<String>>()
    val tables = arrayOf("title", "title_details", "title_relation", "title_recommendation", "list_entry", "list_state")
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
        database.invalidationTracker.createFlow(*tables, emitInitialState = false).collect { seen += it }
    }
    runCurrent()
    return seen
}

internal suspend fun RoomDatabase.execute(sql: String) = useWriterConnection { it.execSQL(sql) }

// Makes such writes to the table fail, as a full disk would, until [repair].
internal suspend fun RoomDatabase.breakWrites(
    table: String,
    vararg events: String = arrayOf("INSERT", "UPDATE", "DELETE"),
) {
    for (event in events) {
        execute("CREATE TEMP TRIGGER broken_${table}_$event BEFORE $event ON $table BEGIN SELECT RAISE(ABORT, 'broken'); END")
    }
}

internal suspend fun RoomDatabase.repair(
    table: String,
    vararg events: String = arrayOf("INSERT", "UPDATE", "DELETE"),
) {
    for (event in events) execute("DROP TRIGGER broken_${table}_$event")
}

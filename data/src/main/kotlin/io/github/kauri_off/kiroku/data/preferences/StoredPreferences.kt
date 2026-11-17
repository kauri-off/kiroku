package io.github.kauri_off.kiroku.data.preferences

import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.data.db.Codes
import io.github.kauri_off.kiroku.data.db.listStatuses
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

// The person's choices in a Preferences file, read and written on [io]; [unexpected] hears once of a choice it cannot read.
class StoredPreferences internal constructor(
    file: File,
    scope: CoroutineScope,
    private val unexpected: (Throwable) -> Unit,
    io: CoroutineDispatcher,
    format: Serializer<Preferences>,
) : PreferencesRepository {
    constructor(
        file: File,
        scope: CoroutineScope,
        unexpected: (Throwable) -> Unit,
    ) : this(file, scope, unexpected, Dispatchers.IO, PreferencesFileSerializer)

    private val store =
        PreferenceDataStoreFactory.create(
            storage = FileStorage(format) { file },
            corruptionHandler =
                ReplaceFileCorruptionHandler { e ->
                    unexpected(PreferencesFailure(e))
                    emptyPreferences()
                },
            scope = CoroutineScope(scope.coroutineContext + io),
        )

    private val data: Flow<Preferences> =
        store.data
            .catch { e ->
                if (e !is IOException) throw e
                unexpected(PreferencesFailure(e))
                emit(emptyPreferences())
            }.flowOn(io)

    // The stored values already reported as unreadable, each once per process.
    private val reported = mutableSetOf<Pair<String, Any?>>()

    override fun observeListSorts(): Flow<Map<ListStatus, TabSort>> =
        data.map { stored -> ListStatus.entries.associateWith { sortOf(stored, it) } }.distinctUntilChanged()

    override suspend fun setListSort(
        status: ListStatus,
        sort: TabSort,
    ) {
        try {
            store.edit { stored ->
                stored[sortKey(status)] = sorts.toColumn(sort.sort)
                val key = directionKey(status)
                val direction = sort.direction
                if (direction != null) stored[key] = directions.toColumn(direction) else stored.remove(key)
            }
        } catch (e: IOException) {
            unexpected(PreferencesFailure(e))
        }
    }

    // The list's sorts are its owner's and go with them (S9); every other choice stays. A disk that fails this throws.
    suspend fun forgetListSorts() {
        try {
            store.edit { stored ->
                stored
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(SORT) || it.name.startsWith(DIRECTION) }
                    .forEach { stored -= it }
            }
        } catch (e: IOException) {
            throw PreferencesFailure(e)
        }
    }

    // Each of the three settings on its own: one that cannot be read is its default, reported once.
    fun observeNotificationSettings(): Flow<NotificationSettings> =
        data
            .map { stored ->
                val defaults = NotificationSettings()
                NotificationSettings(
                    on = choiceOf(stored, NOTIFY_ON, defaults.on) { it as? Boolean },
                    alsoPlanned = choiceOf(stored, NOTIFY_ALSO_PLANNED, defaults.alsoPlanned) { it as? Boolean },
                    delay = choiceOf(stored, NOTIFY_DELAY, defaults.delay) { (it as? String)?.let(delays.values::get) },
                )
            }.distinctUntilChanged()

    // All three at once; a disk that fails this throws.
    suspend fun writeNotificationSettings(settings: NotificationSettings) {
        try {
            store.edit { stored ->
                stored[NOTIFY_ON] = settings.on
                stored[NOTIFY_ALSO_PLANNED] = settings.alsoPlanned
                stored[NOTIFY_DELAY] = delays.toColumn(settings.delay)
            }
        } catch (e: IOException) {
            throw PreferencesFailure(e)
        }
    }

    private fun <T> choiceOf(
        stored: Preferences,
        key: Preferences.Key<*>,
        default: T,
        read: (Any) -> T?,
    ): T {
        val value = stored.asMap()[key] ?: return default
        val chosen = read(value)
        if (chosen == null) unreadable(listOf(key.name to value))
        return chosen ?: default
    }

    private fun sortOf(
        stored: Preferences,
        status: ListStatus,
    ): TabSort {
        val values = stored.asMap()
        val keys = listOf(sortKey(status), directionKey(status))
        val (sort, direction) = keys.map { values[it] }
        if (sort == null && direction == null) return TabSort.defaultFor(status)
        val read = readSort(status, sort, direction)
        if (read == null) unreadable(keys.map { it.name to values[it] })
        return read ?: TabSort.defaultFor(status)
    }

    private fun readSort(
        status: ListStatus,
        sort: Any?,
        direction: Any?,
    ): TabSort? {
        val chosen = (sort as? String)?.let(sorts.values::get)?.takeIf { it in sortsFor(status, withAiring = true) } ?: return null
        val towards =
            when (direction) {
                null -> null
                is String -> directions.values[direction] ?: return null
                else -> return null
            }
        return if ((towards == null) == (chosen.defaultDirection == null)) TabSort(chosen, towards) else null
    }

    private fun unreadable(stored: List<Pair<String, Any?>>) {
        val first = synchronized(reported) { reported.addAll(stored) }
        if (first) unexpected(PreferencesFailure(UnreadableChoice()))
    }

    private class UnreadableChoice : Exception()

    private companion object {
        const val SORT = "list_sort_"
        const val DIRECTION = "list_direction_"

        val NOTIFY_ON = booleanPreferencesKey("notify_on")
        val NOTIFY_ALSO_PLANNED = booleanPreferencesKey("notify_also_planned")
        val NOTIFY_DELAY = stringPreferencesKey("notify_delay")

        val delays =
            Codes(NotificationDelay.entries) {
                when (it) {
                    NotificationDelay.None -> "none"
                    NotificationDelay.HalfHour -> "half_hour"
                    NotificationDelay.Hour -> "hour"
                    NotificationDelay.ThreeHours -> "three_hours"
                }
            }

        fun sortKey(status: ListStatus) = stringPreferencesKey("$SORT${listStatuses.toColumn(status)}")

        fun directionKey(status: ListStatus) = stringPreferencesKey("$DIRECTION${listStatuses.toColumn(status)}")

        val sorts =
            Codes(ListSort.entries) {
                when (it) {
                    ListSort.UpNext -> "up_next"
                    ListSort.Title -> "title"
                    ListSort.LastUpdated -> "last_updated"
                    ListSort.Score -> "score"
                    ListSort.StartDate -> "start_date"
                }
            }

        val directions =
            Codes(SortDirection.entries) {
                when (it) {
                    SortDirection.Ascending -> "ascending"
                    SortDirection.Descending -> "descending"
                }
            }
    }
}

// A choice that could not be read or kept, as a report without the exception's message: its type and stack.
class PreferencesFailure internal constructor(
    cause: Exception,
) : Exception(cause.javaClass.name) {
    init {
        stackTrace = cause.stackTrace
    }
}

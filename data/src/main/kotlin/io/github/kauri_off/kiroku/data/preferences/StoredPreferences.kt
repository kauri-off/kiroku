package io.github.kauri_off.kiroku.data.preferences

import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.link.isAcceptedUserName
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.list.defaultDirection
import io.github.kauri_off.kiroku.core.list.mangaListSorts
import io.github.kauri_off.kiroku.core.list.sortsFor
import io.github.kauri_off.kiroku.core.model.ListStatus
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.notify.NotificationDelay
import io.github.kauri_off.kiroku.core.notify.NotificationSettings
import io.github.kauri_off.kiroku.core.repository.AdultSearchChoice
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.data.db.Codes
import io.github.kauri_off.kiroku.data.db.listStatuses
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.fileRetry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val writes = MutableStateFlow(0L)
    private val adultWrites = Mutex()
    private val mangaSortAccess = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val settings = writes.flatMapLatest { data }

    internal suspend fun <T> withAdultChoice(block: suspend (AdultSearchChoice?) -> T): T =
        adultWrites.withLock {
            val choice =
                try {
                    adultChoice(store.data.first())
                } catch (e: IOException) {
                    unexpected(PreferencesFailure(e))
                    null
                }
            block(choice)
        }

    override fun observeTitleLanguage(): Flow<TitleLanguage> =
        settings
            .map { choiceOf(it, TITLE_LANGUAGE, TitleLanguage.Romaji) { value -> (value as? String)?.let(languages.values::get) } }
            .distinctUntilChanged()

    override fun observeAdultSearchChoice(): Flow<AdultSearchChoice> = settings.map(::adultChoice).distinctUntilChanged()

    private fun adultChoice(stored: Preferences) = AdultSearchChoice(adultIn(stored), stored.asMap()[ADULT_REVISION] as? Long ?: 0L)

    private fun adultIn(stored: Preferences): Boolean = choiceOf(stored, ADULT_IN_SEARCH, false) { it as? Boolean }

    override suspend fun setTitleLanguage(language: TitleLanguage): Outcome<Unit> =
        save { it[TITLE_LANGUAGE] = languages.toColumn(language) }

    override suspend fun changeAdultInSearch(change: (Boolean) -> Boolean): Outcome<Unit> =
        adultWrites.withLock {
            save {
                val before = adultChoice(it)
                val after = change(before.enabled)
                if (before.enabled != after) {
                    it[ADULT_IN_SEARCH] = after
                    it[ADULT_REVISION] = before.revision + 1
                }
            }
        }

    private suspend fun save(change: (MutablePreferences) -> Unit): Outcome<Unit> =
        try {
            edit(change)
            Outcome.Success(Unit)
        } catch (e: IOException) {
            unexpected(PreferencesFailure(e))
            Outcome.Failure(DataError.StorageError)
        }

    private suspend fun edit(change: (MutablePreferences) -> Unit) {
        store.edit { change(it) }
        writes.update { it + 1 }
    }

    override fun observeListSorts(): Flow<Map<ListStatus, TabSort>> =
        data.map { stored -> ListStatus.entries.associateWith { sortOf(stored, it) } }.distinctUntilChanged()

    override suspend fun setListSort(
        status: ListStatus,
        sort: TabSort,
    ) = writeSort(sortKey(status), directionKey(status), sort)

    override suspend fun mangaSorts(owner: SignInId): Map<MangaReadingStatus, TabSort> =
        mangaSortAccess.withLock {
            val prefix = mangaSortPrefix(owner)
            val values = data.first().asMap()
            val statuses =
                values.keys.mapNotNullTo(mutableSetOf()) { key ->
                    key.name.removePrefix(prefix).takeIf { it != key.name }
                }
            statuses
                .mapNotNull { code ->
                    val sortKey = stringPreferencesKey(prefix + code)
                    val directionKey = stringPreferencesKey(mangaDirectionPrefix(owner) + code)
                    val sort = values[sortKey]
                    val direction = values[directionKey]
                    val choice = readSort(mangaListSorts, sort, direction)
                    if (choice == null || code.isBlank()) {
                        unreadable(listOf(sortKey.name to sort, directionKey.name to direction))
                        null
                    } else {
                        MangaReadingStatus(code) to choice
                    }
                }.toMap()
        }

    override suspend fun setMangaListSort(
        owner: SignInId,
        status: MangaReadingStatus,
        sort: TabSort,
        canWrite: suspend () -> Boolean,
    ) {
        require(sort.sort in mangaListSorts)
        mangaSortAccess.withLock {
            if (canWrite()) {
                writeSort(
                    stringPreferencesKey(mangaSortPrefix(owner) + status.code),
                    stringPreferencesKey(mangaDirectionPrefix(owner) + status.code),
                    sort,
                )
            }
        }
    }

    private suspend fun writeSort(
        sortKey: Preferences.Key<String>,
        directionKey: Preferences.Key<String>,
        sort: TabSort,
    ) {
        try {
            edit { stored ->
                stored[sortKey] = sorts.toColumn(sort.sort)
                val direction = sort.direction
                if (direction != null) stored[directionKey] = directions.toColumn(direction) else stored.remove(directionKey)
            }
        } catch (e: IOException) {
            unexpected(PreferencesFailure(e))
        }
    }

    override fun observeOpenedProfiles(): Flow<List<String>> = data.map(::openedOf).distinctUntilChanged()

    override suspend fun updateOpenedProfiles(change: (List<String>) -> List<String>) {
        try {
            edit { stored ->
                val names = change(openedOf(stored))
                if (names.isEmpty()) stored.remove(OPENED) else stored[OPENED] = names.joinToString(NAMES_APART)
            }
        } catch (e: IOException) {
            unexpected(PreferencesFailure(e))
        }
    }

    suspend fun forgetMangaListSorts(owner: SignInId) {
        mangaSortAccess.withLock { removeMangaSorts { it.isMangaSortOf(owner) } }
    }

    suspend fun sweepMangaListSorts(
        session: MalSession,
        owner: SessionOwner,
        retired: (SignInId) -> Boolean,
    ) {
        var attempt = 0
        while (session.withOwner(owner) { reconcileMangaListSorts(owner.id?.takeUnless(retired)) } ==
            Outcome.Failure(DataError.StorageError)
        ) {
            delay(fileRetry(attempt++).toMillis())
        }
    }

    internal suspend fun reconcileMangaListSorts(owner: SignInId?): Outcome<Unit> =
        mangaSortAccess.withLock {
            removeMangaSorts { name -> name.isMangaSort && (owner == null || !name.isMangaSortOf(owner)) }
        }

    private suspend fun removeMangaSorts(remove: (String) -> Boolean): Outcome<Unit> =
        save { stored ->
            stored
                .asMap()
                .keys
                .filter { remove(it.name) }
                .forEach { stored -= it }
        }

    // The list's sorts and the profiles opened are the user's and go with them (S9); every other choice stays. A disk
    // that fails this throws.
    suspend fun forgetUsersChoices() {
        try {
            edit { stored ->
                stored
                    .asMap()
                    .keys
                    .filter { it.name.startsWith(SORT) || it.name.startsWith(DIRECTION) || it == OPENED }
                    .filterNot { it.name.isMangaSort }
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
            edit { stored ->
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
        val read = readSort(sortsFor(status, withAiring = true), sort, direction)
        if (read == null) unreadable(keys.map { it.name to values[it] })
        return read ?: TabSort.defaultFor(status)
    }

    private fun readSort(
        available: List<ListSort>,
        sort: Any?,
        direction: Any?,
    ): TabSort? {
        val chosen = (sort as? String)?.let(sorts.values::get)?.takeIf { it in available } ?: return null
        val towards =
            when (direction) {
                null -> null
                is String -> directions.values[direction] ?: return null
                else -> return null
            }
        return if ((towards == null) == (chosen.defaultDirection == null)) TabSort(chosen, towards) else null
    }

    // Names the app would not open are not a list of opened profiles.
    private fun openedOf(stored: Preferences): List<String> {
        val value = stored.asMap()[OPENED] ?: return emptyList()
        val names = (value as? String)?.split(NAMES_APART)?.takeIf { it.all(::isAcceptedUserName) }
        if (names == null) unreadable(listOf(OPENED.name to value))
        return names.orEmpty()
    }

    private fun unreadable(stored: List<Pair<String, Any?>>) {
        val first = synchronized(reported) { reported.addAll(stored) }
        if (first) unexpected(PreferencesFailure(UnreadableChoice()))
    }

    private class UnreadableChoice : Exception()

    private companion object {
        const val SORT = "list_sort_"
        const val DIRECTION = "list_direction_"
        const val NAMES_APART = "\n"

        val TITLE_LANGUAGE = stringPreferencesKey("title_language")
        val ADULT_IN_SEARCH = booleanPreferencesKey("adult_in_search")
        val ADULT_REVISION = longPreferencesKey("adult_search_revision")
        val languages =
            Codes(TitleLanguage.entries) {
                when (it) {
                    TitleLanguage.Romaji -> "romaji"
                    TitleLanguage.English -> "english"
                    TitleLanguage.Japanese -> "japanese"
                }
            }

        val OPENED = stringPreferencesKey("opened_profiles")

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

        fun mangaSortPrefix(owner: SignInId) = "${SORT}manga_${owner.value.length}_${owner.value}_"

        fun mangaDirectionPrefix(owner: SignInId) = "${DIRECTION}manga_${owner.value.length}_${owner.value}_"

        val String.isMangaSort get() = startsWith("${SORT}manga_") || startsWith("${DIRECTION}manga_")

        fun String.isMangaSortOf(owner: SignInId) = startsWith(mangaSortPrefix(owner)) || startsWith(mangaDirectionPrefix(owner))

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

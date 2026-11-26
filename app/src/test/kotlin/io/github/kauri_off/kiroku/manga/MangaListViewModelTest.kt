package io.github.kauri_off.kiroku.manga

import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.demo.DemoPreferences
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.list.ListSort
import io.github.kauri_off.kiroku.core.list.SortDirection
import io.github.kauri_off.kiroku.core.list.TabSort
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.core.repository.PreferencesRepository
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class MangaListViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owner = SignInId("first")
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private var snapshot = MangaListSnapshot(owner, listOf(mangaEntry), now)
    private val online = MutableStateFlow(false)
    private val sessions = MutableStateFlow<Session>(Session.SignedIn(owner))
    private val preferences = DemoPreferences()
    private val failures = mutableListOf<DataError>()
    private var cache = CompletableDeferred(Unit)
    private var admission = CompletableDeferred(Unit)
    private var connectionAdmission = CompletableDeferred(Unit)
    private var answer = CompletableDeferred<Outcome<MangaListSnapshot>>(Outcome.Failure(DataError.NoConnection))
    private var cachedReads = 0
    private var fetches = 0

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private fun model(
        saved: SavedStateHandle = SavedStateHandle(),
        repo: PreferencesRepository = preferences,
    ) = MangaListViewModel(
        object : MangaListReader {
            override suspend fun stored(owner: SignInId): Outcome<MangaListSnapshot?> {
                cachedReads++
                cache.await()
                return Outcome.Success(snapshot.takeIf { it.owner == owner })
            }

            override suspend fun refresh(
                owner: SignInId,
                canDispatch: () -> Boolean,
            ): Outcome<MangaListSnapshot>? {
                admission.await()
                if (!canDispatch()) return null
                fetches++
                return withContext(NonCancellable) { answer.await() }
            }
        },
        Clock.fixed(now, ZoneOffset.UTC),
        object : Connection {
            override fun observeOnline() = online.onStart { connectionAdmission.await() }
        },
        sessions,
        flowOf(TitleLanguage.Romaji),
        repo,
        saved,
        onRefreshFailed = failures::add,
    )

    @Test
    fun `recreation during a sort write starts with the committed direction and order`() =
        runTest(dispatcher) {
            val reading = mangaEntry.status
            val highScore = mangaEntry.copy(id = MangaId(10), score = 9, updatedAt = now)
            val lowScore = mangaEntry.copy(id = MangaId(20), score = 4, updatedAt = now.minusSeconds(30))
            snapshot = snapshot.copy(entries = listOf(highScore, lowScore))
            val write = CompletableDeferred<Unit>()
            val serial = Mutex()
            val repo =
                object : PreferencesRepository by preferences {
                    override suspend fun mangaSorts(owner: SignInId) = serial.withLock { preferences.mangaSorts(owner) }

                    override suspend fun setMangaListSort(
                        owner: SignInId,
                        status: MangaReadingStatus,
                        sort: TabSort,
                        canWrite: suspend () -> Boolean,
                    ) {
                        serial.withLock {
                            write.await()
                            preferences.setMangaListSort(owner, status, sort, canWrite)
                        }
                    }
                }
            val first = model(repo = repo)
            val recreated = model(repo = repo)
            val shown = showing(first.shown, first.state)
            try {
                runCurrent()
                Snapshot.sendApplyNotifications()
                runCurrent()
                first.control(owner, MangaListEvent.OpenSort(reading))
                first.control(owner, MangaListEvent.SelectSort(ListSort.Score))
                first.control(owner, MangaListEvent.SelectDirection(SortDirection.Ascending))
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(listOf(lowScore, highScore), (first.state.value.content as MangaListContent.Ready).entries)

                shown.cancel()
                first.viewModelScope.cancel()
                showing(recreated.shown, recreated.state)
                runCurrent()
                assertEquals(MangaListContent.Loading, recreated.state.value.content)
                write.complete(Unit)
                runCurrent()
                assertEquals(TabSort(ListSort.Score, SortDirection.Ascending), recreated.state.value.sortFor(reading))
                assertEquals(listOf(lowScore, highScore), (recreated.state.value.content as MangaListContent.Ready).entries)
            } finally {
                write.complete(Unit)
                first.viewModelScope.cancel()
                recreated.viewModelScope.cancel()
            }
        }

    @Test
    fun `an unfamiliar selection stays pending until the first authoritative rows arrive`() =
        runTest(dispatcher) {
            val unfamiliar = MangaReadingStatus("rereading")
            val saved = SavedStateHandle().apply { saveMangaControls(MangaListControls(owner, unfamiliar)) }
            snapshot = snapshot.copy(entries = emptyList(), syncedAt = now.minusSeconds(7 * 24 * 60 * 60))
            answer = CompletableDeferred()
            val vm = model(saved)
            try {
                showing(vm.shown, vm.state)
                runCurrent()
                assertEquals(unfamiliar, vm.state.value.tab)
                val waiting = vm.state.value.content as MangaListContent.Ready
                assertEquals(unfamiliar, waiting.statuses[waiting.pageIndex(unfamiliar)])
                assertEquals(1, fetches)
                val restored = mangaEntry.copy(status = unfamiliar)
                answer.complete(Outcome.Success(snapshot.copy(entries = listOf(restored), syncedAt = now)))
                runCurrent()
                assertEquals(unfamiliar, vm.state.value.tab)
                assertEquals(listOf(restored), (vm.state.value.content as MangaListContent.Ready).pages[unfamiliar])
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `a stale populated cache preserves an unfamiliar selection until refresh confirms it`() =
        runTest(dispatcher) {
            val unfamiliar = MangaReadingStatus("rereading")
            val restored = mangaEntry.copy(status = unfamiliar)
            for (rows in listOf(listOf(restored), listOf(mangaEntry))) {
                val saved = SavedStateHandle().apply { saveMangaControls(MangaListControls(owner, unfamiliar)) }
                snapshot = snapshot.copy(entries = listOf(mangaEntry), syncedAt = now.minusSeconds(7 * 24 * 60 * 60))
                answer = CompletableDeferred()
                val vm = model(saved)
                val shown = showing(vm.shown, vm.state)
                try {
                    runCurrent()
                    val waiting = vm.state.value.content as MangaListContent.Ready
                    val initial = waiting.statuses[waiting.pageIndex(vm.state.value.tab)]
                    vm.control(owner, MangaListEvent.SelectTab(initial))
                    assertEquals(unfamiliar, initial)

                    answer.complete(Outcome.Success(snapshot.copy(entries = rows, syncedAt = now)))
                    runCurrent()
                    val ready = vm.state.value.content as MangaListContent.Ready
                    val settled = ready.statuses[ready.pageIndex(vm.state.value.tab)]
                    vm.control(owner, MangaListEvent.SelectTab(settled))
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    assertEquals(rows.single().status, vm.state.value.tab)
                } finally {
                    shown.cancel()
                    vm.viewModelScope.cancel()
                }
            }
        }

    @Test
    fun `a fresh task restores Manga sort and direction from preferences`() =
        runTest(dispatcher) {
            val reading = mangaEntry.status
            val first = model()
            val shown = showing(first.shown, first.state)
            try {
                runCurrent()
                Snapshot.sendApplyNotifications()
                runCurrent()
                first.control(owner, MangaListEvent.OpenSort(reading))
                first.control(owner, MangaListEvent.SelectSort(ListSort.Score))
                first.control(owner, MangaListEvent.SelectDirection(SortDirection.Ascending))
                first.control(owner, MangaListEvent.StartFilter)
                first.control(owner, MangaListEvent.FilterChanged("previous task"))
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(TabSort(ListSort.Score, SortDirection.Ascending), preferences.mangaSorts(owner)[reading])
            } finally {
                shown.cancel()
                first.viewModelScope.cancel()
            }
            val recreated = model()
            try {
                showing(recreated.shown, recreated.state)
                runCurrent()
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(TabSort(ListSort.Score, SortDirection.Ascending), recreated.state.value.sortFor(reading))
                assertEquals(null, recreated.state.value.filterText)
            } finally {
                recreated.viewModelScope.cancel()
            }
        }

    @Test
    fun `a delayed automatic download orders the first rows after an empty cache`() =
        runTest(dispatcher) {
            snapshot = snapshot.copy(entries = emptyList(), syncedAt = now.minusSeconds(7 * 24 * 60 * 60))
            answer = CompletableDeferred()
            val vm = model()
            try {
                showing(vm.shown, vm.state)
                runCurrent()
                assertEquals(emptyList<Any>(), (vm.state.value.content as MangaListContent.Ready).entries)
                assertEquals(1, fetches)
                val older = mangaEntry.copy(id = MangaId(10), updatedAt = now.minusSeconds(30))
                val newer = mangaEntry.copy(id = MangaId(20), updatedAt = now)
                answer.complete(Outcome.Success(snapshot.copy(entries = listOf(older, newer), syncedAt = now)))
                runCurrent()
                assertEquals(listOf(newer, older), (vm.state.value.content as MangaListContent.Ready).entries)
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `a replacement owner receives no saved Manga filter or sort from the old owner`() =
        runTest(dispatcher) {
            val vm = model()
            try {
                showing(vm.shown, vm.state)
                runCurrent()
                val planned = MangaReadingStatus("plan_to_read")
                vm.control(owner, MangaListEvent.OpenSort(planned))
                vm.control(owner, MangaListEvent.SelectSort(ListSort.Score))
                vm.control(owner, MangaListEvent.SelectDirection(SortDirection.Ascending))
                vm.control(owner, MangaListEvent.DismissSort)
                vm.control(owner, MangaListEvent.StartFilter)
                vm.control(owner, MangaListEvent.FilterChanged("private query"))
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals("private query", vm.state.value.filterText)
                assertEquals(TabSort(ListSort.Score, SortDirection.Ascending), vm.state.value.sortFor(planned))
                assertEquals(defaultMangaSort, vm.state.value.sortFor(mangaEntry.status))
                val replacement = SignInId("replacement")
                snapshot = snapshot.copy(owner = replacement)
                sessions.value = Session.SignedIn(replacement)
                runCurrent()
                Snapshot.sendApplyNotifications()
                runCurrent()

                vm.control(owner, MangaListEvent.StartFilter)
                vm.control(owner, MangaListEvent.SelectTab(planned))
                runCurrent()
                assertEquals(replacement, vm.state.value.owner)
                assertEquals(mangaEntry.status, vm.state.value.tab)
                assertEquals(defaultMangaSort, vm.state.value.sortFor(planned))
                assertEquals(null, vm.state.value.filterText)
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `reconnect retains its refresh while a fresh cache lookup is suspended`() =
        runTest(dispatcher) {
            val vm = model()
            try {
                val firstShow = showing(vm.shown, vm.state)
                runCurrent()
                assertEquals(0, fetches)
                vm.refresh()
                runCurrent()
                assertEquals(1, fetches)
                firstShow.cancel()
                runCurrent()

                val previousReads = cachedReads
                cache = CompletableDeferred()
                showing(vm.shown, vm.state)
                runCurrent()
                assertEquals(previousReads + 1, cachedReads)
                answer = CompletableDeferred(Outcome.Success(snapshot))
                online.value = true
                runCurrent()
                assertEquals(1, fetches)
                cache.complete(Unit)
                runCurrent()
                assertEquals(2, fetches)
                assertEquals(listOf(DataError.NoConnection), failures)
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `a recovery during an admitted refresh retries only if its answer still says offline`() =
        runTest(dispatcher) {
            val updated = snapshot.copy(entries = listOf(mangaEntry.copy(chaptersRead = 99)))
            for (lateError in listOf(null, DataError.NoConnection)) {
                online.value = false
                failures.clear()
                fetches = 0
                answer = CompletableDeferred(Outcome.Failure(DataError.NoConnection))
                val vm = model()
                val screen = showing(vm.shown, vm.state)
                val pending = CompletableDeferred<Outcome<MangaListSnapshot>>()
                try {
                    runCurrent()
                    vm.refresh()
                    runCurrent()
                    assertEquals(1, fetches)

                    answer = pending
                    vm.refresh()
                    runCurrent()
                    assertEquals(2, fetches)
                    online.value = true
                    runCurrent()
                    assertEquals(2, fetches)

                    answer = CompletableDeferred(Outcome.Success(updated))
                    pending.complete(if (lateError == null) Outcome.Success(updated) else Outcome.Failure(lateError))
                    runCurrent()
                    assertEquals(if (lateError == null) 2 else 3, fetches)
                    val content = vm.state.value.content as MangaListContent.Ready
                    assertEquals(updated.entries, content.entries)
                    assertEquals(false, content.refreshing)
                    runCurrent()
                    assertEquals(if (lateError == null) 2 else 3, fetches)
                } finally {
                    pending.complete(Outcome.Failure(DataError.NoConnection))
                    screen.cancel()
                    vm.viewModelScope.cancel()
                    runCurrent()
                }
            }
        }

    @Test
    fun `a second offline pull covered by a title retries once after Back`() =
        runTest(dispatcher) {
            for ((answerAfterBack, firstRetryFails) in listOf(false to false, true to false, true to true)) {
                online.value = false
                fetches = 0
                answer = CompletableDeferred(Outcome.Failure(DataError.NoConnection))
                val vm = model()
                val pending = CompletableDeferred<Outcome<MangaListSnapshot>>()
                val firstShow = showing(vm.shown, vm.state)
                try {
                    runCurrent()
                    vm.refresh()
                    runCurrent()
                    val firstRecovery = if (firstRetryFails) Outcome.Failure(DataError.NoConnection) else Outcome.Success(snapshot)
                    answer = CompletableDeferred(firstRecovery)
                    online.value = true
                    runCurrent()
                    assertEquals(2, fetches)

                    answer = pending
                    vm.refresh()
                    runCurrent()
                    assertEquals(3, fetches)
                    firstShow.cancel()
                    runCurrent()
                    online.value = false
                    runCurrent()
                    if (!answerAfterBack) pending.complete(Outcome.Failure(DataError.NoConnection))
                    runCurrent()
                    online.value = true
                    runCurrent()
                    assertEquals(3, fetches)

                    answer = CompletableDeferred(Outcome.Failure(DataError.NoConnection))
                    val returned = showing(vm.shown, vm.state)
                    try {
                        runCurrent()
                        if (answerAfterBack) {
                            assertEquals(3, fetches)
                            pending.complete(Outcome.Failure(DataError.NoConnection))
                            runCurrent()
                        }
                        assertEquals(4, fetches)
                    } finally {
                        returned.cancel()
                    }
                } finally {
                    pending.complete(Outcome.Failure(DataError.NoConnection))
                    firstShow.cancel()
                    vm.viewModelScope.cancel()
                    runCurrent()
                }
            }
        }

    @Test
    fun `a queued recovery keeps its single attempt when online is first observed after Back`() =
        runTest(dispatcher) {
            val vm = model()
            val manual = CompletableDeferred<Outcome<MangaListSnapshot>>()
            val automatic = CompletableDeferred<Outcome<MangaListSnapshot>>()
            try {
                val firstShow = showing(vm.shown, vm.state)
                runCurrent()
                vm.refresh()
                runCurrent()
                answer = manual
                vm.refresh()
                runCurrent()
                assertEquals(2, fetches)
                online.value = true
                runCurrent()
                assertEquals(2, fetches)

                firstShow.cancel()
                runCurrent()
                manual.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                connectionAdmission = CompletableDeferred()
                answer = automatic
                showing(vm.shown, vm.state)
                runCurrent()
                assertEquals(3, fetches)
                connectionAdmission.complete(Unit)
                runCurrent()
                automatic.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                assertEquals(3, fetches)
            } finally {
                manual.complete(Outcome.Failure(DataError.NoConnection))
                automatic.complete(Outcome.Failure(DataError.NoConnection))
                vm.viewModelScope.cancel()
                runCurrent()
            }
        }

    @Test
    fun `a queued refresh waits for visibility at admission and resumes after Back`() =
        runTest(dispatcher) {
            val vm = model()
            try {
                val firstShow = showing(vm.shown, vm.state)
                runCurrent()
                admission = CompletableDeferred()
                answer = CompletableDeferred(Outcome.Success(snapshot))
                vm.refresh()
                runCurrent()
                firstShow.cancel()
                runCurrent()
                admission.complete(Unit)
                runCurrent()
                assertEquals(0, fetches)

                showing(vm.shown, vm.state)
                runCurrent()
                assertEquals(1, fetches)
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `an admitted cached refresh posts its failure behind a title only for the current owner`() =
        runTest(dispatcher) {
            for (retired in listOf(false, true)) {
                sessions.value = Session.SignedIn(owner)
                failures.clear()
                answer = CompletableDeferred()
                val vm = model()
                try {
                    val screen = showing(vm.shown, vm.state)
                    runCurrent()
                    vm.refresh()
                    runCurrent()
                    screen.cancel()
                    runCurrent()
                    if (retired) {
                        sessions.value = Session.SignedIn(SignInId("replacement"))
                        runCurrent()
                    }
                    answer.complete(Outcome.Failure(DataError.NoConnection))
                    runCurrent()

                    assertEquals(if (retired) emptyList() else listOf(DataError.NoConnection), failures)
                } finally {
                    answer.complete(Outcome.Failure(DataError.NoConnection))
                    vm.viewModelScope.cancel()
                    runCurrent()
                }
            }
        }
}

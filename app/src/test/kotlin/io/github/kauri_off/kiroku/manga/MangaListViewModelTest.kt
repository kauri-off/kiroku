package io.github.kauri_off.kiroku.manga

import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaListReader
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
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
    private val snapshot = MangaListSnapshot(owner, listOf(mangaEntry), now)
    private val online = MutableStateFlow(false)
    private val sessions = MutableStateFlow<Session>(Session.SignedIn(owner))
    private val failures = mutableListOf<DataError>()
    private var cache = CompletableDeferred(Unit)
    private var admission = CompletableDeferred(Unit)
    private var connectionAdmission = CompletableDeferred(Unit)
    private var answer = CompletableDeferred<Outcome<MangaListSnapshot>>(Outcome.Failure(DataError.NoConnection))
    private var cachedReads = 0
    private var fetches = 0

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private fun model() =
        MangaListViewModel(
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
            failures::add,
        )

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

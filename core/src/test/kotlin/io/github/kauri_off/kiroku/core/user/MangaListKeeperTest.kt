package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Titles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MangaListKeeperTest {
    @Test
    fun `readers share pages for one name and retain independent whole lists`() =
        runTest {
            val tail = CompletableDeferred<Unit>()
            val calls = mutableListOf<String>()
            val lists =
                MangaListKeeper(
                    source { name, emit ->
                        calls += name
                        if (name == "sample") {
                            emit(listOf(row(1)))
                            tail.await()
                            emit(listOf(row(1), row(2)))
                        } else {
                            emit(listOf(row(3)))
                        }
                        Outcome.Success(Unit)
                    },
                    backgroundScope,
                    { throw it },
                )
            val states = mutableListOf<NamedListState<MangaListEntry>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lists.observeList("sample").collect { states += it } }
            runCurrent()
            assertEquals(NamedListState.Reading(listOf(row(1)), null, null), states.last())
            val second = mutableListOf<NamedListState<MangaListEntry>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lists.observeList("SAMPLE").collect { second += it } }
            assertEquals(states.last(), second.last())
            assertEquals(NamedListState.Whole(listOf(row(3)), null), lists.observeList("another").first { it is NamedListState.Whole })
            tail.complete(Unit)
            runCurrent()
            assertEquals(NamedListState.Whole(listOf(row(1), row(2)), null), states.last())
            assertEquals(states.last(), second.last())
            assertEquals(listOf("sample", "another"), calls)
        }

    @Test
    fun `only a complete refresh or a confirmed user response replaces cached content`() =
        runTest {
            var answer: Outcome<Unit> = Outcome.Success(Unit)
            var entries = listOf(row(1))
            val lists =
                MangaListKeeper(
                    source { _, emit ->
                        emit(entries)
                        answer
                    },
                    backgroundScope,
                    { throw it },
                )
            val initial = NamedListState.Whole(listOf(row(1)), null)
            assertEquals(initial, lists.observeList("sample").first { it is NamedListState.Whole })
            val states = mutableListOf<NamedListState<MangaListEntry>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { lists.observeList("sample").collect { states += it } }
            answer = Outcome.Failure(DataError.ServerError)
            entries = listOf(row(2))
            assertEquals(answer, lists.refreshList("sample"))
            assertEquals(initial, states.last())

            for (error in listOf(DataError.PrivateList, DataError.UnknownUser)) {
                answer = Outcome.Failure(error)
                assertEquals(answer, lists.refreshList("sample"))
                assertEquals(NamedListState.None(error), states.last())
                assertEquals(NamedListState.None(error), lists.observeList("sample").first())
                answer = Outcome.Success(Unit)
                entries = emptyList()
                lists.refreshList("sample")
                assertEquals(NamedListState.Whole<MangaListEntry>(emptyList(), null), states.last())
            }

            entries = listOf(row(3))
            lists.forget()
            runCurrent()
            assertEquals(NamedListState.Whole(listOf(row(3)), null), states.last())
        }

    @Test
    fun `a refresh started inline by a cached observer joins the published read`() =
        runTest {
            var calls = 0
            val lists =
                MangaListKeeper(
                    source { _, emit ->
                        calls++
                        emit(listOf(row(calls.toLong())))
                        Outcome.Success(Unit)
                    },
                    backgroundScope,
                    { throw it },
                )
            assertEquals(Outcome.Success(Unit), lists.refreshList("sample"))
            val joined = CompletableDeferred<Result<Outcome<Unit>>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                lists.observeList("sample").collect {
                    if (it is NamedListState.Whole && it.again != null && !joined.isCompleted) {
                        joined.complete(runCatching { lists.refreshList("SAMPLE") })
                    }
                }
            }
            assertEquals(Outcome.Success(Unit), lists.refreshList("sample"))
            assertEquals(Outcome.Success(Unit), joined.await().getOrThrow())
            assertEquals(2, calls)
            assertEquals(NamedListState.Whole(listOf(row(2)), null), lists.observeList("sample").first())
        }

    @Test
    fun `a refresh started inline at completion begins a new read`() =
        runTest {
            var calls = 0
            val lists =
                MangaListKeeper(
                    source { _, emit ->
                        calls++
                        emit(listOf(row(calls.toLong())))
                        Outcome.Success(Unit)
                    },
                    backgroundScope,
                    { throw it },
                )
            assertEquals(Outcome.Success(Unit), lists.refreshList("sample"))
            val refreshed = CompletableDeferred<Outcome<Unit>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                lists.observeList("sample").collect {
                    if (it is NamedListState.Whole && it.again == null && it.entries == listOf(row(2))) {
                        refreshed.complete(lists.refreshList("sample"))
                    }
                }
            }
            assertEquals(Outcome.Success(Unit), lists.refreshList("sample"))
            assertEquals(Outcome.Success(Unit), refreshed.await())
            assertEquals(3, calls)
            assertEquals(NamedListState.Whole(listOf(row(3)), null), lists.observeList("sample").first())
        }

    @Test
    fun `a retry started inline after cancellation begins a new read`() =
        runTest {
            var calls = 0
            val lists =
                MangaListKeeper(
                    source { _, emit ->
                        if (++calls == 1) {
                            currentCoroutineContext().cancel()
                            currentCoroutineContext().ensureActive()
                        }
                        emit(listOf(row(2)))
                        Outcome.Success(Unit)
                    },
                    backgroundScope,
                    { throw it },
                )
            val retried = CompletableDeferred<Outcome<Unit>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                lists.observeList("sample").collect {
                    if (it is NamedListState.None && it.error == DataError.ServerError) {
                        retried.complete(lists.refreshList("sample"))
                    }
                }
            }
            runCurrent()
            assertEquals(Outcome.Success(Unit), retried.await())
            assertEquals(2, calls)
            assertEquals(NamedListState.Whole(listOf(row(2)), null), lists.observeList("sample").first())
        }

    private fun source(read: suspend (String, suspend (List<MangaListEntry>) -> Unit) -> Outcome<Unit>): RemoteMangaLists =
        object : RemoteMangaLists {
            override suspend fun read(
                name: String,
                part: suspend (List<MangaListEntry>) -> Unit,
            ) = read(name, part)

            override fun sameUser(
                a: String,
                b: String,
            ) = a.equals(b, ignoreCase = true)
        }

    private fun row(id: Long) = MangaListEntry(MangaId(id), Titles("Title $id"), MangaReadingStatus("reading"))
}

package io.github.kauri_off.kiroku.manga

import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class MangaTitleViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private val online = MutableStateFlow(false)
    private val failures = mutableListOf<DataError>()
    private var reads = 0
    private var answer = CompletableDeferred<Outcome<StoredManga>>()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    @Test
    fun `an offline refresh keeps the cached title and reconnect retries once`() =
        runTest(dispatcher) {
            val stale = StoredManga(mangaFixture, now.minusSeconds(172_800))
            val viewModel = title(stale)
            try {
                assertEquals(MangaTitleContent.Loading, viewModel.state.value.content)
                runCurrent()
                assertEquals(0, reads)

                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(stale.details, shown(viewModel).details)

                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                assertEquals(stale.details, shown(viewModel).details)
                assertEquals(listOf(DataError.NoConnection), failures)

                val updated = stale.details.copy(chapters = 180)
                answer = CompletableDeferred(Outcome.Success(StoredManga(updated, now)))
                online.value = true
                runCurrent()
                assertEquals(2, reads)
                assertEquals(updated, shown(viewModel).details)
                assertEquals(2, shown(viewModel).coverAttempt)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a fresh title opens from cache until the user pulls to refresh`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val viewModel = title(saved)
            try {
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(0, reads)
                assertEquals(saved.details, shown(viewModel).details)
                viewModel.refresh()
                viewModel.refresh()
                runCurrent()
                assertEquals(1, reads)
                assertEquals(true, shown(viewModel).refreshing)
                answer.complete(Outcome.Success(saved))
                runCurrent()
                assertEquals(false, shown(viewModel).refreshing)
                assertEquals(1, shown(viewModel).coverAttempt)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    private fun title(cached: StoredManga): MangaTitleViewModel =
        MangaTitleViewModel(
            mangaFixture.id,
            object : MangaRepository {
                override suspend fun stored(id: MangaId) = Outcome.Success(cached)

                override suspend fun refresh(id: MangaId): Outcome<StoredManga> {
                    reads++
                    return answer.await()
                }
            },
            Clock.fixed(now, ZoneOffset.UTC),
            object : Connection {
                override fun observeOnline() = online
            },
            flowOf(TitleLanguage.Romaji),
            flowOf(Session.SignedOut),
            { failures.add(it) },
        )

    private fun shown(viewModel: MangaTitleViewModel) = viewModel.state.value.content as MangaTitleContent.Shown
}

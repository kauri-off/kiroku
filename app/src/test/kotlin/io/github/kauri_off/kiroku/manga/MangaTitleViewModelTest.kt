package io.github.kauri_off.kiroku.manga

import androidx.lifecycle.viewModelScope
import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.TitleLanguage
import io.github.kauri_off.kiroku.core.repository.Connection
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.wiring.MainDispatcherRule
import io.github.kauri_off.kiroku.wiring.showing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
    private val owners = MutableStateFlow(SessionOwner(null))
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

    @Test
    fun `fresh cache re-show does not erase a failed offline pull`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val viewModel = title(saved)
            try {
                val firstShow = showing(viewModel.state, viewModel.shown)
                runCurrent()
                viewModel.refresh()
                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                firstShow.cancel()
                runCurrent()
                advanceTimeBy(5_001)
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(saved.details, shown(viewModel).details)

                answer = CompletableDeferred(Outcome.Success(saved))
                online.value = true
                runCurrent()
                assertEquals(2, reads)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a second outage can recover even if its error arrives after connectivity returns`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val viewModel = title(saved)
            try {
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                viewModel.refresh()
                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                answer = CompletableDeferred(Outcome.Success(saved))
                online.value = true
                runCurrent()
                assertEquals(2, reads)

                online.value = false
                runCurrent()
                val late = CompletableDeferred<Outcome<StoredManga>>()
                answer = late
                viewModel.refresh()
                runCurrent()
                online.value = true
                runCurrent()
                answer = CompletableDeferred(Outcome.Failure(DataError.NoConnection))
                late.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                assertEquals(4, reads)
                assertEquals(saved.details, shown(viewModel).details)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a cache lookup finishing while hidden sends nothing until the page is shown again`() =
        runTest(dispatcher) {
            val cache = CompletableDeferred<StoredManga?>()
            val viewModel = title(lookup = { cache.await() })
            try {
                val firstShow = showing(viewModel.state, viewModel.shown)
                runCurrent()
                firstShow.cancel()
                runCurrent()
                cache.complete(null)
                runCurrent()
                assertEquals(0, reads)

                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, reads)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a new owner drops the prior snapshot while its own lookup is pending`() =
        runTest(dispatcher) {
            val original = owners.value
            val saved = StoredManga(mangaFixture, now)
            val next = CompletableDeferred<StoredManga?>()
            val viewModel = title(lookup = { if (it === original) saved else next.await() })
            try {
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(saved.details, shown(viewModel).details)
                owners.value = SessionOwner(SignInId("another"))
                runCurrent()
                assertEquals(MangaTitleContent.Loading, viewModel.state.value.content)
                next.complete(null)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(MangaTitleContent.Loading, viewModel.state.value.content)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a hidden screen cannot replay the retired owner's title when it is shown again`() =
        runTest(dispatcher) {
            val original = owners.value
            val saved = StoredManga(mangaFixture, now)
            val next = CompletableDeferred<StoredManga?>()
            val viewModel = title(lookup = { if (it === original) saved else next.await() })
            try {
                val firstShow = showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(saved.details, shown(viewModel).details)
                firstShow.cancel()
                runCurrent()
                advanceTimeBy(5_001)
                owners.value = SessionOwner(null)
                runCurrent()
                assertEquals(MangaTitleContent.Loading, viewModel.state.value.content)

                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(MangaTitleContent.Loading, viewModel.state.value.content)
                assertEquals(0, reads)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a pull deferred during cache lookup still refreshes a fresh row on return`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val held = CompletableDeferred<StoredManga>()
            var lookups = 0
            val viewModel = title(lookup = { if (lookups++ == 0) saved else held.await() })
            try {
                val screen = showing(viewModel.state, viewModel.shown)
                runCurrent()
                viewModel.refresh()
                runCurrent()
                assertEquals(2, lookups)
                screen.cancel()
                runCurrent()
                held.complete(saved)
                runCurrent()
                assertEquals(0, reads)

                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(true, shown(viewModel).refreshing)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a pull deferred during repository preflight sends once after return and survives hiding after dispatch`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val held = CompletableDeferred<Unit>()
            var preflights = 0
            val viewModel =
                title(saved, preflight = {
                    preflights++
                    held.await()
                })
            try {
                val first = showing(viewModel.state, viewModel.shown)
                runCurrent()
                viewModel.refresh()
                runCurrent()
                assertEquals(1, preflights)
                first.cancel()
                runCurrent()
                held.complete(Unit)
                runCurrent()
                assertEquals(0, reads)

                val second = showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, reads)
                second.cancel()
                runCurrent()
                answer.complete(Outcome.Success(saved))
                runCurrent()
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(1, shown(viewModel).coverAttempt)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a replacement owner loads on a brief re-show without reading for the retired owner again`() =
        runTest(dispatcher) {
            val original = owners.value
            val replacement = SessionOwner(SignInId("replacement"))
            val old = StoredManga(mangaFixture, now)
            val updated = old.copy(details = old.details.copy(chapters = 183))
            val lookups = mutableListOf<SessionOwner>()
            val viewModel =
                title(lookup = {
                    lookups += it
                    if (it === original) old else null
                })
            try {
                val first = showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(old.details, shown(viewModel).details)
                first.cancel()
                runCurrent()
                owners.value = replacement
                runCurrent()
                assertEquals(listOf(original), lookups)

                answer.complete(Outcome.Success(updated))
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(listOf(original, replacement), lookups)
                assertEquals(1, reads)
                assertEquals(updated.details, shown(viewModel).details)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `deferred reconnect waits through an offline re-show and sends once when online again`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val held = CompletableDeferred<Unit>()
            var preflights = 0
            val viewModel = title(saved, preflight = { if (++preflights == 2) held.await() })
            try {
                val screen = showing(viewModel.state, viewModel.shown)
                runCurrent()
                viewModel.refresh()
                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                assertEquals(1, reads)

                online.value = true
                runCurrent()
                assertEquals(2, preflights)
                screen.cancel()
                runCurrent()
                online.value = false
                held.complete(Unit)
                runCurrent()
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(listOf(DataError.NoConnection), failures)

                answer = CompletableDeferred(Outcome.Failure(DataError.NoConnection))
                online.value = true
                runCurrent()
                assertEquals(2, reads)
                assertEquals(List(2) { DataError.NoConnection }, failures)

                answer = CompletableDeferred(Outcome.Success(saved))
                online.value = false
                runCurrent()
                online.value = true
                runCurrent()
                assertEquals(3, reads)
                assertEquals(List(2) { DataError.NoConnection }, failures)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `automatic reconnect does not dispatch when preflight completes after an offline re-show`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val held = CompletableDeferred<Unit>()
            var preflights = 0
            val viewModel = title(saved, preflight = { if (++preflights == 2) held.await() })
            try {
                val screen = showing(viewModel.state, viewModel.shown)
                runCurrent()
                viewModel.refresh()
                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                online.value = true
                runCurrent()
                assertEquals(2, preflights)

                screen.cancel()
                runCurrent()
                online.value = false
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                held.complete(Unit)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(listOf(DataError.NoConnection), failures)

                answer = CompletableDeferred(Outcome.Success(saved))
                online.value = true
                runCurrent()
                assertEquals(2, reads)
                assertEquals(listOf(DataError.NoConnection), failures)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a second recovery before admission takes over the queued retry and is spent once`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val held = CompletableDeferred<Unit>()
            var preflights = 0
            val viewModel = title(saved, preflight = { if (++preflights == 2) held.await() })
            try {
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                viewModel.refresh()
                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                online.value = true
                runCurrent()
                assertEquals(2, preflights)
                assertEquals(1, reads)

                online.value = false
                runCurrent()
                online.value = true
                runCurrent()
                held.complete(Unit)
                runCurrent()
                assertEquals(3, preflights)
                assertEquals(2, reads)
                assertEquals(List(2) { DataError.NoConnection }, failures)

                answer = CompletableDeferred(Outcome.Success(saved))
                online.value = false
                runCurrent()
                online.value = true
                runCurrent()
                assertEquals(3, reads)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a second recovery after admission remains available for a late failure`() =
        runTest(dispatcher) {
            val saved = StoredManga(mangaFixture, now)
            val viewModel = title(saved)
            try {
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                viewModel.refresh()
                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()

                val admitted = CompletableDeferred<Outcome<StoredManga>>()
                answer = admitted
                online.value = true
                runCurrent()
                assertEquals(2, reads)
                online.value = false
                runCurrent()
                online.value = true
                runCurrent()
                assertEquals(2, reads)

                answer = CompletableDeferred(Outcome.Failure(DataError.NoConnection))
                admitted.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                assertEquals(3, reads)
                assertEquals(List(3) { DataError.NoConnection }, failures)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `only a shown manga card observes connectivity and observes it once`() =
        runTest(dispatcher) {
            val viewModel = title(StoredManga(mangaFixture, now))
            try {
                runCurrent()
                assertEquals(0, online.subscriptionCount.value)
                val first = showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, online.subscriptionCount.value)
                first.cancel()
                runCurrent()
                assertEquals(0, online.subscriptionCount.value)
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, online.subscriptionCount.value)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a full re-show refreshes stale cache after the recovery was spent`() =
        fullShowAfterSpentRecovery(StoredManga(mangaFixture, now.minusSeconds(172_800)))

    @Test
    fun `a full re-show reads a missing title before a delayed connectivity observation`() = fullShowAfterSpentRecovery(null)

    private fun fullShowAfterSpentRecovery(cached: StoredManga?) =
        runTest(dispatcher) {
            val observeAgain = CompletableDeferred<Unit>()
            var subscriptions = 0
            var lookups = 0
            val viewModel =
                title(
                    lookup = {
                        lookups++
                        cached
                    },
                    connection =
                        object : Connection {
                            override fun observeOnline() =
                                flow {
                                    if (++subscriptions == 2) observeAgain.await()
                                    online.collect { emit(it) }
                                }
                        },
                )
            try {
                val first = showing(viewModel.state, viewModel.shown)
                runCurrent()
                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                online.value = true
                runCurrent()
                assertEquals(2, reads)

                first.cancel()
                runCurrent()
                advanceTimeBy(5_001)
                val before = lookups
                val updated = cached?.copy(details = mangaFixture.copy(chapters = 184), fetchedAt = now)
                if (updated != null) answer = CompletableDeferred(Outcome.Success(updated))
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(before + 1, lookups)
                assertEquals(3, reads)

                observeAgain.complete(Unit)
                runCurrent()
                assertEquals(3, reads)
                if (updated != null) {
                    assertEquals(updated.details, shown(viewModel).details)
                } else {
                    assertEquals(
                        MangaTitleContent.Failed(MangaTitleProblem.ReadFailed(DataError.NoConnection)),
                        viewModel.state.value.content,
                    )
                }
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `full re-show survives a reconnect held before cache lookup completes`() = fullShowDuringReconnect(holdCache = true)

    @Test
    fun `full re-show survives a reconnect deferred at dispatch`() = fullShowDuringReconnect(holdCache = false)

    private fun fullShowDuringReconnect(holdCache: Boolean) =
        runTest(dispatcher) {
            val stale = StoredManga(mangaFixture, now.minusSeconds(172_800))
            val held = CompletableDeferred<Unit>()
            val observeAgain = CompletableDeferred<Unit>()
            var lookups = 0
            var preflights = 0
            var subscriptions = 0
            val viewModel =
                title(
                    lookup = {
                        if (++lookups == 2 && holdCache) held.await()
                        stale
                    },
                    preflight = { if (++preflights == 2 && !holdCache) held.await() },
                    connection =
                        object : Connection {
                            override fun observeOnline() =
                                flow {
                                    if (++subscriptions == 2) observeAgain.await()
                                    online.collect { emit(it) }
                                }
                        },
                )
            try {
                val screen = showing(viewModel.state, viewModel.shown)
                runCurrent()
                answer.complete(Outcome.Failure(DataError.NoConnection))
                runCurrent()
                online.value = true
                runCurrent()
                assertEquals(2, lookups)
                assertEquals(1, reads)

                screen.cancel()
                runCurrent()
                advanceTimeBy(5_001)
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                val updated = stale.copy(details = mangaFixture.copy(chapters = 184), fetchedAt = now)
                answer = CompletableDeferred(Outcome.Success(updated))
                held.complete(Unit)
                runCurrent()
                assertEquals(2, reads)
                assertEquals(updated.details, shown(viewModel).details)

                observeAgain.complete(Unit)
                runCurrent()
                assertEquals(2, reads)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `fresh sparse details keep the row cover and score without refreshing`() =
        runTest(dispatcher) {
            val summary = mangaFixture.copy(synopsis = null)
            val sparse = mangaFixture.copy(posterUrl = null, meanScore = null, chapters = 180)
            val viewModel =
                title(
                    StoredManga(sparse, now),
                    opened = MangaTitleSummary(summary, TitleLanguage.Romaji),
                )
            try {
                assertEquals(summary, shown(viewModel).details)
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(0, reads)
                assertEquals(sparse.copy(posterUrl = summary.posterUrl, meanScore = summary.meanScore), shown(viewModel).details)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `the row summary lasts through a held cold read only for its opening owner`() =
        runTest(dispatcher) {
            val owner = SignInId("opening-owner")
            val account = MutableStateFlow<Session>(Session.SignedIn(owner))
            owners.value = SessionOwner(owner)
            val preview = mangaFixture.copy(synopsis = null, volumes = null, chapters = null, meanScore = null)
            val viewModel = title(account = account, opened = MangaTitleSummary(preview, TitleLanguage.Romaji, owner))
            try {
                assertEquals(MangaTitleContent.Loading, viewModel.state.value.content)
                showing(viewModel.state, viewModel.shown)
                runCurrent()
                assertEquals(1, reads)
                assertEquals(preview, shown(viewModel).details)

                answer.complete(Outcome.Success(StoredManga(mangaFixture, now)))
                runCurrent()
                assertEquals(mangaFixture, shown(viewModel).details)

                val next = SignInId("next-owner")
                answer = CompletableDeferred()
                account.value = Session.SignedIn(next)
                owners.value = SessionOwner(next)
                runCurrent()
                assertEquals(2, reads)
                assertEquals(MangaTitleContent.Loading, viewModel.state.value.content)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    private fun title(
        cached: StoredManga? = null,
        lookup: suspend (SessionOwner) -> StoredManga? = { cached },
        preflight: suspend () -> Unit = {},
        account: Flow<Session> = flowOf(Session.SignedOut),
        connection: Connection =
            object : Connection {
                override fun observeOnline() = online
            },
        opened: MangaTitleSummary? = null,
    ): MangaTitleViewModel =
        MangaTitleViewModel(
            mangaFixture.id,
            object : MangaRepository {
                override fun observeOwner() = owners

                override suspend fun stored(
                    id: MangaId,
                    owner: SessionOwner,
                ) = Outcome.Success(lookup(owner))

                override suspend fun refresh(
                    id: MangaId,
                    owner: SessionOwner,
                    canDispatch: () -> Boolean,
                ): Outcome<StoredManga>? {
                    preflight()
                    if (!canDispatch()) return null
                    reads++
                    return answer.await()
                }
            },
            Clock.fixed(now, ZoneOffset.UTC),
            connection,
            flowOf(TitleLanguage.Romaji),
            account,
            onRefreshFailed = { failures.add(it) },
            opened = opened,
        )

    private fun shown(viewModel: MangaTitleViewModel) = viewModel.state.value.content as MangaTitleContent.Shown
}

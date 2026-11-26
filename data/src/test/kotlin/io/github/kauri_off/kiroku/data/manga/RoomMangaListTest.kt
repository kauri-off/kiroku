package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaListEntry
import io.github.kauri_off.kiroku.core.model.MangaListNumber
import io.github.kauri_off.kiroku.core.model.MangaListSnapshot
import io.github.kauri_off.kiroku.core.model.MangaReadingStatus
import io.github.kauri_off.kiroku.core.model.Session
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.model.owner
import io.github.kauri_off.kiroku.core.repository.MangaListEdit
import io.github.kauri_off.kiroku.core.repository.MangaListEditResult
import io.github.kauri_off.kiroku.core.repository.MangaListRemovalResult
import io.github.kauri_off.kiroku.core.repository.MangaListStatus
import io.github.kauri_off.kiroku.data.anime.edited
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.network.MalJson
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.TestClock
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.OwnerRecordings
import io.github.kauri_off.kiroku.data.session.Attempt
import io.github.kauri_off.kiroku.data.session.SessionFixture
import io.github.kauri_off.kiroku.data.session.TokenGrant
import io.github.kauri_off.kiroku.data.session.TokenReply
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class RoomMangaListTest {
    @get:Rule
    val folder = TemporaryFolder()

    @get:Rule
    internal val network = NetworkFixture()

    private val context = RuntimeEnvironment.getApplication()
    private val name = "personal-manga.db"
    private var database = KirokuDatabase.open(context, name)
    private val sessions by lazy { SessionFixture(folder.newFolder()) }
    private val session by lazy { sessions.open() }
    private val clock = TestClock(Instant.parse("2026-11-25T15:00:00.123456789Z"))
    private val reported = mutableListOf<Throwable>()
    private var response: suspend (SignInId) -> Outcome<List<MangaListEntry>> = { error("Unexpected fetch") }

    private var write: suspend (SignInId, MangaId, MangaListEdit) -> MangaWriteResult = { _, _, _ -> error("Unexpected patch") }

    private var delete: suspend (SignInId, MangaId) -> MangaWriteResult = { _, _ -> error("Unexpected delete") }

    @After
    fun close() {
        runBlocking { sessions.close() }
        database.close()
        context.deleteDatabase(name)
    }

    private fun repository() =
        RoomMangaList(
            { database },
            session,
            { owner, _ -> response(owner) },
            clock,
            unexpected = { reported += it },
            writer = { owner, id, edit -> write(owner, id, edit) },
            remover = { owner, id -> delete(owner, id) },
        )

    private suspend fun signIn(name: String): SignInId {
        assertEquals(Outcome.Success(Unit), session.signIn(TokenGrant(name, "refresh-$name")))
        return checkNotNull(session.currentSession()?.owner)
    }

    private fun entry(id: Long = 17) =
        MangaListEntry(
            id = MangaId(id),
            titles = Titles("Narrow Roads", "Roads", "Road", listOf("Side Road")),
            status = MangaReadingStatus("unrecognized-reading-status"),
            posterUrl = "https://example.test/cover.png",
            chaptersRead = 0,
            volumesRead = 3,
            score = 8,
            updatedAt = clock.now.minusSeconds(1),
        )

    @Test
    fun `removal leaves confirmed rows visible until full read back and serializes a queued patch`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            val other = entry(18)
            response = { Outcome.Success(listOf(initial, other)) }
            list.refresh(owner)
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            delete = { asked, id ->
                assertEquals(owner to initial.id, asked to id)
                sent.complete(Unit)
                release.await()
                response = { Outcome.Success(listOf(other)) }
                MangaWriteResult.Accepted
            }
            val observed =
                async {
                    withTimeout(5_000) {
                        list.observe(owner).first { it == Outcome.Success(MangaListSnapshot(owner, listOf(other), clock.now)) }
                    }
                }
            val pending = async { list.remove(owner, initial) }
            val patch =
                try {
                    withTimeout(5_000) { sent.await() }
                    assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial, other), null)), list.stored(owner))
                    async(start = CoroutineStart.UNDISPATCHED) { list.edit(owner, initial.id, MangaListEdit(score = 2)) }
                } finally {
                    release.complete(Unit)
                }

            val expected = Outcome.Success(MangaListSnapshot(owner, listOf(other), clock.now))
            assertEquals(MangaListRemovalResult.Removed, pending.await())
            assertEquals(expected, observed.await())
            assertEquals(MangaListEditResult.Failed(DataError.NotFound), patch.await())
            database.close()
            database = KirokuDatabase.open(context, name)
            assertEquals(expected, repository().stored(owner))
            assertTrue(reported.isEmpty())
        }

    @Test
    fun `an ambiguous removal owes a complete read and only an unchanged trusted baseline can retry`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            var sent = 0
            delete = { _, _ ->
                sent++
                MangaWriteResult.Unknown(DataError.ServerError)
            }
            response = { Outcome.Failure(DataError.NoConnection) }
            assertEquals(MangaListRemovalResult.ReadRequired(DataError.NoConnection), list.remove(owner, initial))
            assertEquals(MangaListRemovalResult.RefreshRequired, list.remove(owner, initial, retry = true))
            assertEquals(1, sent)

            val changed = initial.copy(updatedAt = clock.now)
            response = { Outcome.Success(listOf(changed)) }
            list.refresh(owner)
            assertEquals(MangaListRemovalResult.Discarded, list.remove(owner, initial, retry = true))
            val unverified = initial.copy(unverifiedNumbers = setOf(MangaListNumber.Score))
            response = { Outcome.Success(listOf(unverified)) }
            list.refresh(owner)
            assertEquals(MangaListRemovalResult.Discarded, list.remove(owner, unverified, retry = true))
            assertEquals(1, sent)

            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            assertEquals(MangaListRemovalResult.StillPresent(canRetry = true), list.remove(owner, initial, retry = true))
            assertEquals(2, sent)
        }

    @Test
    fun `a late removal cannot read or delete rows for a replacement sign in`() =
        runBlocking {
            val old = signIn("a")
            val list = repository()
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(old)
            val sent = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            delete = { asked, _ ->
                assertEquals(old, asked)
                sent.complete(Unit)
                release.await()
                MangaWriteResult.Accepted
            }
            val pending = async { list.remove(old, initial) }
            val replacement =
                try {
                    withTimeout(5_000) { sent.await() }
                    val owner = signIn("b")
                    response = { asked ->
                        assertEquals(owner, asked)
                        Outcome.Success(listOf(initial.copy(score = 2)))
                    }
                    owner to list.refresh(owner)
                } finally {
                    release.complete(Unit)
                }
            assertEquals(MangaListRemovalResult.Failed(DataError.ListEnded), pending.await())
            assertEquals(MangaListRemovalResult.Failed(DataError.ListEnded), list.remove(old, initial, retry = true))
            assertEquals(replacement.second, list.stored(replacement.first))
        }

    @Test
    fun `empty success and 404 require a complete read to establish removal`() =
        runBlocking {
            val owner = signIn("replay")
            val pages = OwnerRecordings.mangaPages()
            OwnerRecordings.read("manga removal read-back") {
                val live = RoomMangaList({ database }, session, network.http, clock) { false }
                val firstPage = MalJson.parseToJsonElement(pages.first().body).jsonObject
                val emptyPage = firstPage.edited(listOf("data"), JsonArray(emptyList())).edited(listOf("paging"), JsonObject(emptyMap()))
                for (page in pages) network.api.enqueue(answer(page.status, page.body))
                val before = (live.refresh(owner) as Outcome.Success).value
                val rows = firstPage.getValue("data").jsonArray
                val removedRow = rows.last().jsonObject
                val removedNode = removedRow.getValue("node").jsonObject
                val initial = before.entries.single { it.id.value == removedNode.getValue("id").jsonPrimitive.long }
                repeat(pages.size) { check(network.api.received().method == "GET") }
                network.api.enqueue(answer(404, ""))
                val partial = firstPage.edited(listOf("data"), JsonArray(rows.dropLast(1)))
                val paging = partial.getValue("paging").jsonObject
                val next = paging.getValue("next").jsonPrimitive.content
                val nextWithOneRow = next.replace("offset=2", "offset=1")
                network.api.enqueue(answer(200, partial.edited(listOf("paging", "next"), JsonPrimitive(nextWithOneRow)).toString()))
                network.api.enqueue(answer(400, ""))
                check(live.remove(owner, initial) == MangaListRemovalResult.ReadRequired(DataError.InvalidRequest))
                check(network.api.received().method == "DELETE")
                repeat(2) { check(network.api.received().method == "GET") }
                check(live.stored(owner) == Outcome.Success(before.copy(syncedAt = null)))
                val count = network.api.requestCount
                check(live.remove(owner, initial, retry = true) == MangaListRemovalResult.RefreshRequired)
                check(network.api.requestCount == count)

                for (status in listOf(200, 204, 404)) {
                    for (page in pages) network.api.enqueue(answer(page.status, page.body))
                    val baseline = (live.refresh(owner) as Outcome.Success).value.entries.first()
                    repeat(pages.size) { check(network.api.received().method == "GET") }
                    network.api.enqueue(answer(status, ""))
                    network.api.enqueue(answer(200, emptyPage.toString()))
                    check(live.remove(owner, baseline) == MangaListRemovalResult.Removed)
                    val request = network.api.received()
                    check(request.method == "DELETE" && request.url.encodedPath == "/v2/manga/${baseline.id.value}/my_list_status")
                    check(network.api.received().method == "GET")
                    check((live.stored(owner) as Outcome.Success).value?.entries == emptyList<MangaListEntry>())
                }
            }
        }

    @Test
    fun `a pending edit leaves Room confirmed and the read back publishes actual values`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            val changes = MangaListEdit(status = MangaListStatus.Completed, chaptersRead = 50, volumesRead = 0, score = 0)
            val confirmed = changes.appliedTo(initial).copy(chaptersRead = 27, updatedAt = clock.now)
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            val sent = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            write = { asked, id, edit ->
                assertEquals(Triple(owner, initial.id, changes), Triple(asked, id, edit))
                sent.complete(Unit)
                finish.await()
                response = { Outcome.Success(listOf(confirmed)) }
                MangaWriteResult.Accepted
            }
            val snapshots = list.observe(owner).filterIsInstance<Outcome.Success<MangaListSnapshot?>>().mapNotNull { it.value }
            val observed = async { withTimeout(5_000) { snapshots.first { it.entries == listOf(confirmed) } } }
            val pending = async { list.edit(owner, initial.id, changes) }
            try {
                withTimeout(5_000) { sent.await() }
                assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), null)), list.stored(owner))
            } finally {
                finish.complete(Unit)
            }
            assertEquals(MangaListEditResult.Accepted(Outcome.Success(confirmed)), pending.await())
            assertEquals(MangaListSnapshot(owner, listOf(confirmed), clock.now), observed.await())
        }

    @Test
    fun `an older refresh settles before the edit can patch and confirm`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            val confirmed = initial.copy(score = 2)
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            response = {
                reading.complete(Unit)
                release.await()
                Outcome.Success(listOf(initial))
            }
            write = { _, _, _ ->
                assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), null)), list.stored(owner))
                response = { Outcome.Success(listOf(confirmed)) }
                MangaWriteResult.Accepted
            }
            val refreshing = async { list.refresh(owner) }
            withTimeout(5_000) { reading.await() }
            val editing = async(start = CoroutineStart.UNDISPATCHED) { list.edit(owner, initial.id, MangaListEdit(score = 2)) }
            release.complete(Unit)
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), clock.now)), refreshing.await())
            assertEquals(MangaListEditResult.Confirmed(confirmed), editing.await())
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(confirmed), clock.now)), list.stored(owner))
        }

    @Test
    fun `definite refusal keeps the confirmed row and timestamp`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            val before = list.refresh(owner)
            response = { error("A rejection should require no download") }
            write = { _, _, _ -> MangaWriteResult.Refused(DataError.Refused) }

            assertEquals(MangaListEditResult.Failed(DataError.Refused), list.edit(owner, initial.id, MangaListEdit(score = 4)))
            assertEquals(before, list.stored(owner))
            assertTrue(reported.isEmpty())
        }

    @Test
    fun `an ambiguous patch that cannot be read back blocks retry until authoritative refresh`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            val changes = MangaListEdit(chaptersRead = 5)
            val actual = changes.appliedTo(initial)
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            var patches = 0
            write = { _, _, _ ->
                patches++
                MangaWriteResult.Unknown(DataError.ServerError)
            }
            response = { Outcome.Failure(DataError.NoConnection) }

            assertEquals(
                MangaListEditResult.Uncertain(DataError.ServerError, needsRefresh = true),
                list.edit(owner, initial.id, changes),
            )
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), null)), list.stored(owner))
            assertEquals(MangaListEditResult.RefreshRequired, list.edit(owner, initial.id, changes))
            assertEquals(1, patches)

            response = { Outcome.Success(listOf(actual)) }
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(actual), clock.now)), list.refresh(owner))
            assertEquals(MangaListEditResult.Discarded, list.edit(owner, initial.id, changes, retryFrom = initial))
            assertEquals(1, patches)
        }

    @Test
    fun `a retry behind refresh sends only fields that remain eligible`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            val changed = initial.copy(chaptersRead = 3)
            val confirmed = changed.copy(score = 2)
            val reading = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            response = {
                reading.complete(Unit)
                release.await()
                Outcome.Success(listOf(changed))
            }
            write = { _, _, changes ->
                assertEquals(MangaListEdit(score = 2), changes)
                response = { Outcome.Success(listOf(confirmed)) }
                MangaWriteResult.Accepted
            }
            val refreshing = async { list.refresh(owner) }
            withTimeout(5_000) { reading.await() }
            val requested = MangaListEdit(status = MangaListStatus.Completed, chaptersRead = 5, volumesRead = 10, score = 2)
            val retry = async(start = CoroutineStart.UNDISPATCHED) { list.edit(owner, initial.id, requested, retryFrom = initial) }
            release.complete(Unit)

            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(changed), clock.now)), refreshing.await())
            assertEquals(MangaListEditResult.Confirmed(confirmed), retry.await())
        }

    @Test
    fun `an accepted edit with failed read back can retry reading alone`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            var patches = 0
            write = { _, _, _ ->
                patches++
                MangaWriteResult.Accepted
            }
            response = { Outcome.Success(listOf(initial, initial)) }

            assertEquals(
                MangaListEditResult.Accepted(Outcome.Failure(DataError.ServerError)),
                list.edit(owner, initial.id, MangaListEdit(score = 1)),
            )
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), null)), list.stored(owner))

            response = { Outcome.Success(listOf(initial)) }
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), clock.now)), list.refresh(owner))
            assertEquals(1, patches)
            assertTrue(reported.isEmpty())
        }

    @Test
    fun `an ambiguous patch can settle Room from a successful read back`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            val actual = initial.copy(volumesRead = 1)
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            write = { _, _, _ ->
                response = { Outcome.Success(listOf(actual)) }
                MangaWriteResult.Unknown(DataError.NoConnection)
            }

            assertEquals(
                MangaListEditResult.Confirmed(actual),
                list.edit(owner, initial.id, MangaListEdit(volumesRead = 1)),
            )
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(actual), clock.now)), list.stored(owner))
        }

    @Test
    fun `a cancelled write leaves its last confirmed values due for refresh after reopening`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            val sent = CompletableDeferred<Unit>()
            write = { _, _, _ ->
                sent.complete(Unit)
                awaitCancellation()
            }
            val pending = async { list.edit(owner, initial.id, MangaListEdit(score = 1)) }
            withTimeout(5_000) { sent.await() }
            pending.cancelAndJoin()

            database.close()
            database = KirokuDatabase.open(context, name)
            val reopened = repository()
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), null)), reopened.stored(owner))
            assertEquals(MangaListEditResult.RefreshRequired, reopened.edit(owner, initial.id, MangaListEdit(score = 1)))
            val actual = initial.copy(score = 1)
            response = { Outcome.Success(listOf(actual)) }
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(actual), clock.now)), reopened.refresh(owner))
        }

    @Test
    fun `cancelling an admitted delete keeps the row stale after reopening`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(owner)
            val sent = CompletableDeferred<Unit>()
            delete = { _, _ ->
                sent.complete(Unit)
                awaitCancellation()
            }
            val pending = async { list.remove(owner, initial) }
            withTimeout(5_000) { sent.await() }
            pending.cancelAndJoin()

            database.close()
            database = KirokuDatabase.open(context, name)
            val reopened = repository()
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), null)), reopened.stored(owner))
            delete = { _, _ -> error("DELETE must wait for refresh") }
            assertEquals(MangaListRemovalResult.RefreshRequired, reopened.remove(owner, initial, retry = true))
            response = { Outcome.Success(emptyList()) }
            val settled = Outcome.Success(MangaListSnapshot(owner, emptyList(), clock.now))
            assertEquals(settled, reopened.refresh(owner))
            assertEquals(settled, reopened.stored(owner))
        }

    @Test
    fun `late patch success cannot read back or overwrite a replacement sign in`() =
        runBlocking {
            val old = signIn("a")
            val list = repository()
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            list.refresh(old)
            val sent = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            write = { owner, _, _ ->
                assertEquals(old, owner)
                sent.complete(Unit)
                finish.await()
                MangaWriteResult.Accepted
            }
            val pending = async { list.edit(old, initial.id, MangaListEdit(score = 2)) }
            val replacement =
                try {
                    withTimeout(5_000) { sent.await() }
                    val owner = signIn("b")
                    response = { asked ->
                        assertEquals(owner, asked)
                        Outcome.Success(listOf(initial.copy(score = 3)))
                    }
                    val snapshot = list.refresh(owner)
                    assertEquals(Outcome.Failure(DataError.ListEnded), list.observe(old).first())
                    owner to snapshot
                } finally {
                    finish.complete(Unit)
                }
            assertEquals(MangaListEditResult.Failed(DataError.ListEnded), pending.await())
            assertEquals(replacement.second, list.stored(replacement.first))
            assertEquals(Outcome.Failure(DataError.ListEnded), list.stored(old))
        }

    @Test
    fun `an empty PATCH acknowledgement and unchanged recorded pages do not confirm the edit`() =
        runBlocking {
            val owner = signIn("replay")
            val pages = OwnerRecordings.mangaPages()
            OwnerRecordings.read("manga edit read-back") {
                val list = RoomMangaList({ database }, session, network.http, clock) { false }
                for (page in pages) network.api.enqueue(answer(page.status, page.body))
                val before = (list.refresh(owner) as Outcome.Success).value
                val entry = before.entries.first()
                val edit = MangaListEdit(score = if (entry.score == 10) 9 else 10)
                network.api.enqueue(answer(204, ""))
                for (page in pages) network.api.enqueue(answer(page.status, page.body))

                check(list.edit(owner, entry.id, edit) == MangaListEditResult.Uncertain(DataError.ServerError, needsRefresh = false))
                check(list.stored(owner) == Outcome.Success(before))
                repeat(pages.size) { check(network.api.received().method == "GET") }
                val patch = network.api.received()
                check(patch.method == "PATCH" && patch.url.encodedPath == "/v2/manga/${entry.id.value}/my_list_status")
                repeat(pages.size) { check(network.api.received().method == "GET") }
                check(network.api.requestCount == 2 * pages.size + 1)
            }
        }

    @Test
    fun `usable recorded fields can confirm a write with unusable acknowledgement`() =
        runBlocking {
            val owner = signIn("replay")
            val pages = OwnerRecordings.mangaPages()
            OwnerRecordings.read("manga edit evidence") {
                val list = RoomMangaList({ database }, session, network.http, clock) { false }
                for (page in pages) network.api.enqueue(answer(page.status, page.body))
                val before = (list.refresh(owner) as Outcome.Success).value
                val acknowledgements =
                    listOf(
                        answer(204, ""),
                        answer(200, "{"),
                        answer(200, """{"status":broken}"""),
                        answer(200, """{"status":NaN}"""),
                        answer(200, """{"score":00}"""),
                        answer(202, """{"queued":true}"""),
                    )
                for (ack in acknowledgements) {
                    for (requestedAlreadyPresent in listOf(false, true)) {
                        val entry = before.entries.first { it.recordedScore != null }
                        val requested = if (requestedAlreadyPresent) checkNotNull(entry.recordedScore) else (entry.recordedScore!! + 1) % 11
                        network.api.enqueue(ack)
                        for (page in pages) network.api.enqueue(answer(page.status, page.body))
                        val result = list.edit(owner, entry.id, MangaListEdit(score = requested))
                        if (requestedAlreadyPresent) {
                            check(result == MangaListEditResult.Confirmed(entry))
                        } else {
                            check(result == MangaListEditResult.Uncertain(DataError.ServerError, needsRefresh = false))
                        }
                    }
                }
            }
        }

    @Test
    fun `only an explicit zero can prove an unrated baseline is unchanged on retry`() =
        runBlocking {
            val owner = signIn("replay")
            val pages = OwnerRecordings.mangaPages()
            OwnerRecordings.read("manga score evidence") {
                val list = RoomMangaList({ database }, session, network.http, clock) { false }
                val firstPage = MalJson.parseToJsonElement(pages.first().body).jsonObject
                val firstRow =
                    firstPage
                        .getValue("data")
                        .jsonArray
                        .first()
                        .jsonObject
                val id =
                    MangaId(
                        firstRow
                            .getValue("node")
                            .jsonObject
                            .getValue("id")
                            .jsonPrimitive.long,
                    )
                val scorePath = listOf("data", "0", "list_status", "score")

                fun score(value: JsonElement?) {
                    network.api.enqueue(answer(200, firstPage.edited(scorePath, value).toString()))
                    for (page in pages.drop(1)) network.api.enqueue(answer(page.status, page.body))
                }

                score(JsonPrimitive(0))
                val baseline = (list.refresh(owner) as Outcome.Success).value.entries.single { it.id == id }
                check(baseline.recordedScore == 0 && baseline.score == null)
                val requested = MangaListEdit(score = 7)
                for (unusable in listOf(null, JsonNull, JsonPrimitive("invalid"), JsonPrimitive("0"), JsonPrimitive(-1))) {
                    network.api.enqueue(answer(204, ""))
                    score(unusable)
                    val result = list.edit(owner, id, requested)
                    check(result == MangaListEditResult.Uncertain(DataError.ServerError, needsRefresh = true))
                    val cached = (list.stored(owner) as Outcome.Success).value?.entries?.single { it.id == id }
                    check(cached?.recordedScore == null)
                    val sent = network.api.requestCount
                    check(list.edit(owner, id, requested, retryFrom = baseline) == MangaListEditResult.Discarded)
                    check(network.api.requestCount == sent)
                }

                val placeholder = "INVALID_SCORE_TEST_TOKEN"
                network.api.enqueue(answer(204, ""))
                network.api.enqueue(
                    answer(200, firstPage.edited(scorePath, JsonPrimitive(placeholder)).toString().replace("\"$placeholder\"", "00")),
                )
                for (page in pages.drop(1)) network.api.enqueue(answer(page.status, page.body))
                check(
                    list.edit(owner, id, MangaListEdit(score = 0)) ==
                        MangaListEditResult.Uncertain(DataError.ServerError, needsRefresh = true),
                )
                val sent = network.api.requestCount
                check(list.edit(owner, id, requested, retryFrom = baseline) == MangaListEditResult.Discarded)
                check(network.api.requestCount == sent)

                val repairedPage =
                    firstPage
                        .edited(scorePath, JsonPrimitive("7"))
                        .edited(listOf("data", "0", "list_status", "num_chapters_read"), JsonPrimitive("12"))
                        .edited(listOf("data", "0", "list_status", "num_volumes_read"), JsonPrimitive("3"))
                network.api.enqueue(answer(200, repairedPage.toString()))
                for (page in pages.drop(1)) network.api.enqueue(answer(page.status, page.body))
                val repaired = (list.refresh(owner) as Outcome.Success).value.entries.single { it.id == id }
                check(repaired.score == 7 && repaired.chaptersRead == 12 && repaired.volumesRead == 3)
                check(repaired.recordedScore == null && repaired.recordedChaptersRead == null && repaired.recordedVolumesRead == null)
                database.close()
                database = KirokuDatabase.open(context, name)
                val reopened = RoomMangaList({ database }, session, network.http, clock) { false }
                check((reopened.stored(owner) as Outcome.Success).value?.entries?.single { it.id == id } == repaired)

                score(JsonPrimitive(0))
                reopened.refresh(owner)
                val stored = (reopened.stored(owner) as Outcome.Success).value?.entries?.single { it.id == id }
                check(stored?.recordedScore == 0)
                network.api.enqueue(answer(204, ""))
                score(JsonPrimitive(7))
                val result = reopened.edit(owner, id, requested, retryFrom = baseline)
                check(result is MangaListEditResult.Confirmed && result.entry.score == 7)
            }
        }

    @Test
    fun `expiry before an OkHttp follow up keeps the refresh requirement after reopening`() =
        runBlocking {
            val owner = signIn("a")
            val initial = entry()
            response = { Outcome.Success(listOf(initial)) }
            repository().refresh(owner)
            val live = RoomMangaList({ database }, session, network.http, clock) { false }
            val release = CountDownLatch(1)
            network.answers {
                check(release.await(5, TimeUnit.SECONDS))
                MockResponse
                    .Builder()
                    .code(503)
                    .setHeader("Retry-After", "0")
                    .build()
            }

            val writing = async { live.edit(owner, initial.id, MangaListEdit(score = 1)) }
            try {
                assertEquals("PATCH", withContext(Dispatchers.IO) { network.api.received() }.method)
                sessions.answer = { TokenReply.Answer(401, "") }
                assertEquals(Outcome.Failure(DataError.SessionExpired), session.authorised<Unit>(owner) { Attempt.Rejected })
                assertEquals(Session.Expired(owner), session.currentSession())
            } finally {
                release.countDown()
            }
            assertEquals(MangaListEditResult.Uncertain(DataError.ServerError, needsRefresh = true), writing.await())
            assertEquals(1, network.api.requestCount)

            database.close()
            database = KirokuDatabase.open(context, name)
            assertEquals(Outcome.Success(MangaListSnapshot(owner, listOf(initial), null)), repository().stored(owner))
        }

    @Test
    fun `buffered rows are discarded after replacement or retirement`() =
        runBlocking {
            for (end in listOf(OwnerEnd.Replaced, OwnerEnd.SigningOut, OwnerEnd.Retired)) {
                val owner = signIn("a")
                val old = entry()
                val seen = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val observedRead = CompletableDeferred<Unit>()
                val cleanupStarted = CompletableDeferred<Unit>()
                val finishCleanup = CompletableDeferred<Unit>()
                val checks = AtomicInteger()
                val bufferedRead = AtomicInteger(Int.MAX_VALUE)
                val durableRetirement = AtomicBoolean(false)
                val list =
                    RoomMangaList(
                        { database },
                        session,
                        { asked, _ -> response(asked) },
                        clock,
                        unexpected = { reported += it },
                        retired = {
                            if (it == owner && checks.incrementAndGet() == bufferedRead.get()) observedRead.complete(Unit)
                            durableRetirement.get()
                        },
                    )
                response = { Outcome.Success(listOf(old)) }
                list.refresh(owner)
                val delivered = mutableListOf<Outcome<MangaListSnapshot?>>()
                val observing =
                    async {
                        list.observe(owner).take(2).collect {
                            delivered += it
                            if (delivered.size == 1) {
                                seen.complete(Unit)
                                release.await()
                            }
                        }
                    }
                try {
                    withTimeout(5_000) { seen.await() }
                    bufferedRead.set(checks.get() + 3)
                    response = { Outcome.Success(listOf(old.copy(score = 2))) }
                    list.refresh(owner)
                    withTimeout(5_000) { observedRead.await() }
                    when (end) {
                        OwnerEnd.Replaced -> signIn("b")
                        OwnerEnd.Retired -> durableRetirement.set(true)
                        OwnerEnd.SigningOut -> {
                            async {
                                session.signOutIfCurrent(owner) {
                                    cleanupStarted.complete(Unit)
                                    finishCleanup.await()
                                }
                            }
                            withTimeout(5_000) { cleanupStarted.await() }
                            assertEquals(owner, session.currentSession()?.owner)
                        }
                    }
                    release.complete(Unit)
                    withTimeout(5_000) { observing.await() }
                    assertEquals(
                        end.name,
                        listOf(Outcome.Success(MangaListSnapshot(owner, listOf(old), clock.now)), Outcome.Failure(DataError.ListEnded)),
                        delivered,
                    )
                } finally {
                    release.complete(Unit)
                    finishCleanup.complete(Unit)
                }
            }
        }

    private enum class OwnerEnd { Replaced, SigningOut, Retired }

    @Test
    fun `a first 401 with failed renewal goes no further than the refused patch`() =
        runBlocking {
            val owner = signIn("replay")
            val pages = OwnerRecordings.mangaPages()
            OwnerRecordings.read("manga edit renewal refusal") {
                val list = RoomMangaList({ database }, session, network.http, clock) { false }
                for (page in pages) network.api.enqueue(answer(page.status, page.body))
                val before = (list.refresh(owner) as Outcome.Success).value
                sessions.answer = { TokenReply.NoAnswer(DataError.NoConnection) }
                network.api.enqueue(answer(401, ""))
                network.api.enqueue(answer(403, """{"error":"forbidden"}"""))

                val result = list.edit(owner, before.entries.first().id, MangaListEdit(score = 1))
                check(result == MangaListEditResult.Failed(DataError.NoConnection))
                check(list.stored(owner) == Outcome.Success(before))
                check(network.api.requestCount == pages.size + 1)
                check(sessions.grants.size == 1)
            }
        }

    @Test
    fun `complete snapshots replace only the current list and survive reopening`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val first = entry()
            val second = entry(22).copy(score = null, chaptersRead = null, updatedAt = null)
            assertEquals(Outcome.Success(null), list.stored(owner))

            response = { asked ->
                assertEquals(owner, asked)
                Outcome.Success(listOf(second, first))
            }
            val saved = MangaListSnapshot(owner, listOf(first, second), clock.now)
            assertEquals(Outcome.Success(saved), list.refresh(owner))
            assertEquals(Outcome.Success(saved), list.stored(owner))

            database.close()
            database = KirokuDatabase.open(context, name)
            val reopened = repository()
            assertEquals(Outcome.Success(saved), reopened.stored(owner))

            clock.now = clock.now.plusSeconds(20)
            response = { Outcome.Success(listOf(second)) }
            val reduced = MangaListSnapshot(owner, listOf(second), clock.now)
            assertEquals(Outcome.Success(reduced), reopened.refresh(owner))
            assertEquals(Outcome.Success(reduced), reopened.stored(owner))

            clock.now = clock.now.plusSeconds(20)
            response = { Outcome.Success(emptyList()) }
            val empty = MangaListSnapshot(owner, emptyList(), clock.now)
            assertEquals(Outcome.Success(empty), reopened.refresh(owner))
            assertEquals(Outcome.Success(empty), reopened.stored(owner))
        }

    @Test
    fun `a failed download duplicate or storage failure leaves both entries and freshness intact`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val older = entry()
            response = { Outcome.Success(listOf(older)) }
            val saved = list.refresh(owner)

            clock.now = clock.now.plusSeconds(60)
            response = { Outcome.Failure(DataError.NoConnection) }
            assertEquals(Outcome.Failure(DataError.NoConnection), list.refresh(owner))
            assertEquals(saved, list.stored(owner))

            response = { Outcome.Success(listOf(entry(22), entry(18), entry(22))) }
            assertEquals(Outcome.Failure(DataError.ServerError), list.refresh(owner))
            assertEquals(saved, list.stored(owner))

            response = { Outcome.Success(listOf(entry(22).copy(updatedAt = Instant.MAX))) }
            assertEquals(Outcome.Failure(DataError.StorageError), list.refresh(owner))
            assertEquals(saved, list.stored(owner))
            assertEquals(1, reported.size)
        }

    @Test
    fun `a thrown source failure preserves the snapshot and reports no answer contents`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            response = { Outcome.Success(listOf(entry())) }
            val saved = list.refresh(owner)

            clock.now = clock.now.plusSeconds(60)
            response = { error("private response") }
            assertEquals(Outcome.Failure(DataError.ServerError), list.refresh(owner))
            assertEquals(saved, list.stored(owner))
            assertEquals(IllegalStateException::class.java.name, reported.single().message)
            assertNull(reported.single().cause)
        }

    @Test
    fun `a new signed out owner triggers cleanup even when the collector misses the intervening sign in`() =
        runBlocking {
            val a = signIn("a")
            val list = repository()
            response = { Outcome.Success(listOf(entry())) }
            assertTrue(list.refresh(a) is Outcome.Success)
            session.signOut()

            val initial = CompletableDeferred<SessionOwner>()
            val release = CompletableDeferred<Unit>()
            val sweep =
                async {
                    session.observeOwner().take(2).collect { owner ->
                        if (initial.complete(owner)) {
                            release.await()
                        } else {
                            assertNull(owner.id)
                            assertEquals(Outcome.Success(Unit), list.forgetRetired(owner))
                        }
                    }
                }
            try {
                val old = initial.await()
                assertNull(old.id)
                signIn("b")
                assertEquals(Outcome.Failure(DataError.ListEnded), list.forgetRetired(old))
                session.signOut()
                assertEquals(Outcome.Failure(DataError.ListEnded), list.forgetRetired(old))
                assertTrue(database.mangaList().entries(a.value).isNotEmpty())
                release.complete(Unit)
                withTimeout(5_000) { sweep.await() }
                assertTrue(database.mangaList().entries(a.value).isEmpty())
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun `a retired download and cleanup cannot replace the new owner's same manga`() =
        runBlocking {
            val a = signIn("a")
            val list = repository()
            val epochA = session.observeOwner().first()
            val aEntry = entry().copy(score = 4)
            response = { Outcome.Success(listOf(aEntry)) }
            assertEquals(Outcome.Success(MangaListSnapshot(a, listOf(aEntry), clock.now)), list.refresh(a))

            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            response = {
                waiting.complete(Unit)
                release.await()
                Outcome.Success(emptyList())
            }
            val stale = async { list.refresh(a) }
            waiting.await()
            try {
                session.signOut()
                val b = signIn("b")
                assertEquals(Outcome.Failure(DataError.ListEnded), list.stored(a))
                assertEquals(Outcome.Success(null), list.stored(b))
                val bEntry = entry().copy(score = 9)
                response = { Outcome.Success(listOf(bEntry)) }
                val bSnapshot = MangaListSnapshot(b, listOf(bEntry), clock.now)
                assertEquals(Outcome.Success(bSnapshot), withTimeout(5_000) { list.refresh(b) })
                release.complete(Unit)
                assertEquals(Outcome.Failure(DataError.ListEnded), stale.await())
                assertEquals(Outcome.Failure(DataError.ListEnded), list.forgetRetired(epochA))
                assertEquals(Outcome.Success(bSnapshot), list.stored(b))
                assertNull(database.mangaList().state(a.value))
                assertTrue(database.mangaList().entries(a.value).isEmpty())

                session.signOut()
                assertEquals(Outcome.Success(Unit), list.forgetRetired(session.observeOwner().first()))
                assertNull(database.mangaList().state(b.value))
                assertTrue(database.mangaList().entries(b.value).isEmpty())
            } finally {
                release.complete(Unit)
            }
        }

    @Test
    fun `refreshes run in sequence for the same repository while cached data stays readable`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val seen = mutableListOf<SignInId>()
            val waiting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            response = {
                seen += it
                if (seen.size == 1) {
                    waiting.complete(Unit)
                    release.await()
                }
                Outcome.Success(listOf(entry(seen.size.toLong())))
            }
            val first = async { list.refresh(owner) }
            waiting.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { list.refresh(owner) }
            try {
                assertEquals(listOf(owner), seen)
                assertEquals(Outcome.Success(null), list.stored(owner))
            } finally {
                release.complete(Unit)
            }
            val firstSnapshot = (first.await() as Outcome.Success).value
            val secondSnapshot = (second.await() as Outcome.Success).value
            assertEquals(MangaId(1), firstSnapshot.entries.single().id)
            assertEquals(MangaId(2), secondSnapshot.entries.single().id)
            assertEquals(Outcome.Success(secondSnapshot), list.stored(owner))
        }

    @Test
    fun `the source checks visibility after waiting for the refresh queue`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            val inSource = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            response = {
                calls++
                inSource.complete(Unit)
                release.await()
                Outcome.Success(listOf(entry()))
            }
            val visible = AtomicBoolean(true)
            val admitted = async { list.refresh(owner, visible::get) }
            inSource.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { list.refresh(owner, visible::get) }
            visible.set(false)
            release.complete(Unit)

            assertTrue(admitted.await() is Outcome.Success)
            assertNull(queued.await())
            assertEquals(1, calls)
        }

    @Test
    fun `an expiry before send admission keeps the snapshot and reports session expired`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            response = { Outcome.Success(listOf(entry())) }
            val cached = list.refresh(owner)

            sessions.answer = { TokenReply.Answer(400, "") }
            response = {
                assertEquals(Outcome.Failure(DataError.SessionExpired), session.authorised<Unit> { Attempt.Rejected })
                Outcome.Failure(DataError.ListEnded)
            }

            assertEquals(Outcome.Failure(DataError.SessionExpired), list.refresh(owner))
            assertEquals(cached, list.stored(owner))
        }

    @Test
    fun `an expired sign in still reads its own cache and starts no download`() =
        runBlocking {
            val owner = signIn("a")
            val list = repository()
            response = { Outcome.Success(listOf(entry())) }
            val cached = list.refresh(owner)

            sessions.answer = { TokenReply.Answer(400, "") }
            val expired = session.authorised<Unit> { Attempt.Rejected }
            assertEquals(Outcome.Failure(DataError.SessionExpired), expired)
            response = { error("An expired sign-in cannot fetch") }

            assertEquals(cached, list.stored(owner))
            assertEquals(Outcome.Failure(DataError.SessionExpired), list.refresh(owner))
        }
}

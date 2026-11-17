package io.github.kauri_off.kiroku.core.user

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours

// What a source of other users' lists must do, whatever it reads them from; one passes it by subclassing.
@OptIn(ExperimentalCoroutinesApi::class)
abstract class RemoteUserListsContract {
    // The source under test and what it reads from: a public list of more than one part, a private one, a name nobody has.
    class Subject(
        val source: RemoteUserLists,
        val public: String,
        // The titles of the public user's list, in its order.
        val titles: List<AnimeId>,
        val private: String,
        val nobody: String,
        // How many parts the source has asked for so far, of any user.
        val asked: () -> Int,
        // From now on every request fails, or is answered again; gives the failure such a read ends with.
        val failing: (Boolean) -> DataError,
        // The public user under another spelling the source takes for the same user, where it has one.
        val alias: String? = null,
    )

    protected abstract fun subject(scope: TestScope): Subject

    private class Read(
        val parts: List<UserListPart>,
        val end: Outcome<Unit>,
    ) {
        val titles: List<AnimeId> get() = parts.flatMap { part -> part.entries.map { it.anime.id } }
    }

    private suspend fun RemoteUserLists.readAll(
        name: String,
        each: suspend (UserListPart) -> Unit = {},
    ): Read {
        val parts = mutableListOf<UserListPart>()
        val end =
            read(name) {
                parts += it
                each(it)
            }
        return Read(parts, end)
    }

    @Test
    fun `the parts give every entry once, in order`() =
        runTest {
            val subject = subject(this)
            val read = subject.source.readAll(subject.public)
            assertEquals(Outcome.Success(Unit), read.end)
            assertTrue(read.parts.size > 1)
            assertEquals(subject.titles, read.titles)
            assertEquals(subject.titles.size, subject.titles.toSet().size)
        }

    @Test
    fun `a total a part names is the size of the whole list`() =
        runTest {
            val subject = subject(this)
            val read = subject.source.readAll(subject.public)
            assertEquals(emptyList<Int?>(), read.parts.map { it.total }.filter { it != null && it != subject.titles.size })
        }

    @Test
    fun `every read starts from the list's start`() =
        runTest {
            val subject = subject(this)
            repeat(2) { assertEquals(subject.titles, subject.source.readAll(subject.public).titles) }
        }

    @Test
    fun `the next part is asked only once the last one was taken`() =
        runTest {
            val subject = subject(this)
            var taken = 0
            val askedWhileTaking = mutableListOf<Int>()
            subject.source.readAll(subject.public) {
                taken++
                delay(1.hours)
                askedWhileTaking += subject.asked()
            }
            assertTrue(taken > 1)
            assertEquals((1..taken).toList(), askedWhileTaking)
        }

    @Test
    fun `a read that is cancelled asks for nothing more`() =
        runTest {
            val subject = subject(this)
            var taken = 0
            val reading =
                launch {
                    subject.source.read(subject.public) {
                        taken++
                        awaitCancellation()
                    }
                }
            advanceTimeBy(1.hours)
            runCurrent()
            assertEquals(1, taken)
            reading.cancelAndJoin()
            advanceTimeBy(1.hours)
            runCurrent()
            assertEquals(1, subject.asked())
        }

    @Test
    fun `a private list ends as a private list and gives no part`() =
        runTest {
            val subject = subject(this)
            val read = subject.source.readAll(subject.private)
            assertEquals(Outcome.Failure(DataError.PrivateList), read.end)
            assertEquals(emptyList<UserListPart>(), read.parts)
        }

    @Test
    fun `a name nobody has ends as an unknown name and gives no part`() =
        runTest {
            val subject = subject(this)
            val read = subject.source.readAll(subject.nobody)
            assertEquals(Outcome.Failure(DataError.UnknownUser), read.end)
            assertEquals(emptyList<UserListPart>(), read.parts)
        }

    @Test
    fun `a read that fails ends with the failure, and the next read gives the list`() =
        runTest {
            val subject = subject(this)
            val failure = subject.failing(true)
            assertFalse(failure.ofUser)
            for (name in listOf(subject.public, subject.private, subject.nobody)) {
                val read = subject.source.readAll(name)
                assertEquals(name, Outcome.Failure(failure), read.end)
                assertEquals(name, emptyList<UserListPart>(), read.parts)
            }
            subject.failing(false)
            assertEquals(subject.titles, subject.source.readAll(subject.public).titles)
        }

    @Test
    fun `a failure half-way ends the read with it, and no part after it is asked`() =
        runTest {
            val subject = subject(this)
            lateinit var failure: DataError
            val read = subject.source.readAll(subject.public) { failure = subject.failing(true) }
            assertEquals(Outcome.Failure(failure), read.end)
            assertEquals(1, read.parts.size)
            assertEquals(subject.titles.take(read.titles.size), read.titles)
            val asked = subject.asked()
            advanceTimeBy(1.hours)
            runCurrent()
            assertEquals(asked, subject.asked())
        }

    @Test
    fun `whether two names are one user is an equivalence that keeps the three users apart`() =
        runTest {
            val subject = subject(this)
            val names = listOfNotNull(subject.public, subject.private, subject.nobody, subject.alias)
            val same = names.associateWith { a -> names.filter { b -> subject.source.sameUser(a, b) }.toSet() }
            val users = listOf(setOfNotNull(subject.public, subject.alias), setOf(subject.private), setOf(subject.nobody))
            assertEquals(names.associateWith { name -> users.single { name in it } }, same)
        }

    @Test
    fun `a name the source takes for the same user reads the same list`() =
        runTest {
            val subject = subject(this)
            val alias = subject.alias ?: return@runTest
            val read = subject.source.readAll(alias)
            assertEquals(Outcome.Success(Unit), read.end)
            assertEquals(subject.titles, read.titles)
        }
}

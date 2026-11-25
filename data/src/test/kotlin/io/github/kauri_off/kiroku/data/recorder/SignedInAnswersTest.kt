package io.github.kauri_off.kiroku.data.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

// Section 11.1: no answer of the signed-in recorder enters the repository, or a test's output.
class SignedInAnswersTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `no tracked file has the name of a signed-in answer`() {
        val tracked = git("ls-files", "-z").split('\u0000').filter { it.isNotEmpty() }

        assertTrue("data/build.gradle.kts" in tracked)
        assertEquals(emptyList<String>(), tracked.filter { isSignedInAnswerFile(it.substringAfterLast('/')) })
    }

    @Test
    fun `a signed-in answer is told by its name and its status`() {
        assertTrue(isSignedInAnswerFile("own_list_2.200.json"))
        assertTrue(isSignedInAnswerFile("removed.200.txt"))
        assertTrue(isSignedInAnswerFile("suggestions_first.200.json"))
        assertTrue(isSignedInAnswerFile("suggestions_next.403.txt"))
        assertTrue(isSignedInAnswerFile("manga_list_25.200.json"))
        assertFalse(isSignedInAnswerFile("own_list_2.json"))
        assertFalse(isSignedInAnswerFile("list_public_1.200.json"))
        assertFalse(isSignedInAnswerFile("manga_list_0.200.json"))
    }

    @Test
    fun `the folder of the signed-in answers is refused in a checkout or a worktree`() {
        val outside = temporary.newFolder("outside", "owner-recordings")
        val checkout = temporary.newFolder("checkout").also { File(it, ".git").mkdir() }
        val worktree = temporary.newFolder("worktree").also { File(it, ".git").writeText("gitdir: elsewhere") }

        val link =
            Files
                .createSymbolicLink(
                    File(temporary.root, "link").toPath(),
                    File(checkout, "owner").apply { mkdir() }.toPath(),
                ).toFile()

        assertFalse(insideACheckout(outside))
        assertTrue(insideACheckout(File(checkout, ".reference/owner-recordings")))
        assertTrue(insideACheckout(File(worktree, "build")))
        assertTrue(insideACheckout(link))
        assertEquals(outside.canonicalFile, ownerFolder(outside.path))
        for (inside in listOf(File(checkout, ".reference/owner-recordings"), link)) {
            assertThrows(IllegalStateException::class.java) { ownerFolder(inside.path) }
        }
        assertFalse(insideACheckout(OwnerRecordings.folder))
    }

    @Test
    fun `a failure over a signed-in answer names the answer and nothing of what it holds`() {
        val failure = assertThrows(AssertionError::class.java) { OwnerRecordings.read("profile") { error("planted-value") } }

        assertEquals("profile could not be read", failure.message)
        assertNull(failure.cause)
    }

    private fun isSignedInAnswerFile(file: String): Boolean {
        val match = Regex("""([a-z0-9_]+)\.\d{3}\.(json|txt)""").matchEntire(file) ?: return false
        return isSignedInAnswer(match.groupValues[1])
    }

    private fun git(vararg arguments: String): String {
        val top = run("rev-parse", "--show-toplevel", directory = File(".")).trim()
        return run(*arguments, directory = File(top))
    }

    private fun run(
        vararg arguments: String,
        directory: File,
    ): String {
        val process = ProcessBuilder("git", *arguments).directory(directory).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ${arguments.first()}", 0, process.waitFor())
        return output
    }
}

package io.github.kauri_off.kiroku.data.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Section 11.1: the recorded answers of Tenrai are the recorder's, one per request of its list, each a case's status.
class TenraiAnswersTest {
    private val files: List<File> =
        File(checkNotNull(javaClass.getResource("/tenrai/recorded")).toURI()).listFiles().orEmpty().sortedBy { it.name }

    @Test
    fun `every file is named by the recorder's list, and every request of the list has its answer`() {
        val answers = files.filter { it.name != HEADERS_NOTE }

        assertTrue(files.any { it.name == HEADERS_NOTE })
        for (file in answers) assertTrue(file.name, Regex("""[a-z0-9_]+\.(200|404)\.json""").matches(file.name))
        assertEquals(TenraiRequests.map { it.name }.sorted(), answers.map { it.name.substringBefore('.') })
    }
}

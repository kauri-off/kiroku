package io.github.kauri_off.kiroku.core.user

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The port, the keeper and the surface of other users' lists say nothing of the service the lists come from.
class UserListNamesTest {
    private val services = setOf("myanimelist", "anilist", "tenrai")
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val word = Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+")

    // "MAL" as a word of a name, or a service's whole name anywhere in one.
    private fun String.saysService(): Boolean =
        word.findAll(this).any { it.value.lowercase() == "mal" } || services.any { it in replace("_", "").lowercase() }

    private val address = Regex("""://|\bwww\.|\bhttps?\b""")
    private val statusCode = Regex("""(?<![\w.])[1-5]\d\d(?![\d_.])""")

    // Kotlin source without its line comments; a string literal stays, as what it holds is said too.
    private fun code(source: String): String =
        source.lines().joinToString("\n") { line ->
            var quoted = false
            var end = line.length
            for (i in line.indices) {
                when {
                    line[i] == '"' && (i == 0 || line[i - 1] != '\\') -> quoted = !quoted
                    !quoted && line.startsWith("//", i) -> end = minOf(end, i)
                }
            }
            line.substring(0, end)
        }

    private fun problems(
        name: String,
        source: String,
    ): List<String> =
        code(source).lines().flatMapIndexed { index, line ->
            listOfNotNull(
                "names a service".takeIf { identifier.findAll(line).any { it.value.saysService() } },
                "holds an address".takeIf { address.containsMatchIn(line) },
                "holds a status code".takeIf { statusCode.containsMatchIn(line) },
            ).map { "$name:${index + 1} $it: ${line.trim()}" }
        }

    private val main: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
            .resolve("core/src/main/kotlin/io/github/kauri_off/kiroku/core")

    // The declarations of [names] in a file that holds others too, each up to the end of its body.
    private fun declarations(
        file: File,
        names: List<String>,
    ): String =
        names.joinToString("\n") { name ->
            val lines = file.readLines().dropWhile { !it.startsWith("interface $name ") }
            check(lines.isNotEmpty()) { "${file.name} declares no $name" }
            val ends: (String) -> Boolean = if (lines.first().endsWith("{")) ({ it == "}" }) else ({ it.isBlank() })
            lines.take(lines.indexOfFirst(ends) + 1).joinToString("\n")
        }

    @Test
    fun `the port, the keeper and the surface name no service and hold no address or status code`() {
        val files =
            main
                .resolve("user")
                .listFiles()
                .orEmpty()
                .sortedBy { it.name }
        val sources = files.associate { it.name to it.readText() }
        for (declared in listOf("interface RemoteUserLists", "class UserListKeeper", "sealed interface UserListState")) {
            assertTrue(declared, sources.values.any { declared in it })
        }
        val surface = declarations(main.resolve("repository/Repositories.kt"), listOf("OwnProfile", "UserLists", "UserRepository"))
        assertTrue(surface, listOf("observeOwnProfile", "observeList", "refreshList", "sameUser", "UserLists").all { it in surface })
        assertEquals(emptyList<String>(), (sources + ("the surface" to surface)).flatMap { problems(it.key, it.value) })
    }

    @Test
    fun `a name that says the service, an address and a status code are each found`() {
        val broken =
            mapOf(
                "val malName: String" to "names a service",
                "fun fromMal(name: String): UserListPart" to "names a service",
                "class MyAnimeListUsers : RemoteUserLists" to "names a service",
                "interface MALUserLists" to "names a service",
                "val source = \"anilist\"" to "names a service",
                "val tenrai_users = 2" to "names a service",
                "val profile = \"https://example.invalid/profile/\" + name" to "holds an address",
                "val host = \"www.example.invalid\" // The site" to "holds an address",
                "if (code == 403) return Outcome.Failure(DataError.PrivateList)" to "holds a status code",
                "val unknown = 404L" to "holds a status code",
            )
        for ((line, problem) in broken) assertEquals(listOf("sample:1 $problem: ${code(line).trim()}"), problems("sample", line))
    }

    @Test
    fun `a comment, a word that only contains the letters and another number are not`() {
        val fine =
            """
            // As MAL's answer: 403 for a private list, at https://example.invalid/.
            val normal = minimal + thermal.format(decimal)
            const val USERS_HELD = 3
            const val PART_SIZE = 1_000
            val wait = 0.250
            val text = "a // b"
            """.trimIndent()
        assertEquals(emptyList<String>(), problems("sample", fine))
        assertEquals("val text = \"a // b\"", code(fine).lines().last())
    }
}

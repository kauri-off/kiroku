package io.github.kauri_off.kiroku.rules

import java.io.File

// A string literal of Kotlin source, without its template expressions; where it starts in the code and on which line.
data class Literal(
    val text: String,
    val line: Int,
    val offset: Int,
)

// Splits Kotlin source into code (comments and literals blanked out) and its string literals.
class KotlinSource(
    source: String,
) {
    val literals = mutableListOf<Literal>()
    val code: String

    private val s = source
    private var i = 0
    private var line = 1
    private val out = StringBuilder()

    init {
        while (i < s.length) codeStep(stopAtBrace = false)
        code = out.toString()
    }

    // Returns true when a closing brace of a template expression was reached.
    private fun codeStep(stopAtBrace: Boolean): Boolean {
        val c = s[i]
        when {
            s.startsWith("//", i) -> skipWhile { s[i] != '\n' }
            s.startsWith("/*", i) -> blockComment()
            s.startsWith("\"\"\"", i) -> string(raw = true)
            c == '"' -> string(raw = false)
            c == '\'' -> charLiteral()
            stopAtBrace && c == '}' -> return true
            c == '{' && stopAtBrace -> {
                emit(c)
                while (i < s.length && !codeStep(stopAtBrace = true)) Unit
                if (i < s.length) emit(s[i])
            }
            else -> emit(c)
        }
        return false
    }

    private fun emit(c: Char) {
        if (c == '\n') line++
        out.append(c)
        i++
    }

    private fun blank() {
        if (s[i] == '\n') {
            line++
            out.append('\n')
        } else {
            out.append(' ')
        }
        i++
    }

    private fun skipWhile(condition: () -> Boolean) {
        while (i < s.length && condition()) blank()
    }

    private fun blockComment() {
        var depth = 0
        do {
            when {
                s.startsWith("/*", i) -> {
                    depth++
                    blank()
                    blank()
                }
                s.startsWith("*/", i) -> {
                    depth--
                    blank()
                    blank()
                }
                else -> blank()
            }
        } while (i < s.length && depth > 0)
    }

    private fun charLiteral() {
        blank()
        while (i < s.length && s[i] != '\'') {
            if (s[i] == '\\') blank()
            blank()
        }
        if (i < s.length) blank()
    }

    private fun string(raw: Boolean) {
        val start = line
        val offset = out.length
        val text = StringBuilder()
        out.append('"')
        repeat(if (raw) 3 else 1) { i++ }
        while (i < s.length) {
            when {
                raw && s.startsWith("\"\"\"", i) && !s.startsWith("\"\"\"\"", i) -> break
                !raw && s[i] == '"' -> break
                !raw && s[i] == '\\' -> {
                    blank()
                    val escaped = s[i]
                    blank()
                    text.append(
                        when (escaped) {
                            'u' ->
                                s
                                    .substring(i, i + 4)
                                    .toInt(16)
                                    .toChar()
                                    .also { repeat(4) { blank() } }
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            'b' -> '\b'
                            else -> escaped
                        },
                    )
                }
                s.startsWith("\${", i) -> {
                    out.append("\${")
                    i += 2
                    while (i < s.length && !codeStep(stopAtBrace = true)) Unit
                    if (i < s.length) emit('}')
                }
                s[i] == '$' && i + 1 < s.length && s[i + 1].isJavaIdentifierStart() -> {
                    blank()
                    skipWhile { s[i].isJavaIdentifierPart() }
                }
                else -> {
                    text.append(s[i])
                    blank()
                }
            }
        }
        repeat(if (raw) 3 else 1) { if (i < s.length) i++ }
        out.append('"')
        literals += Literal(text.toString(), start, offset)
    }

    companion object {
        fun of(file: File) = KotlinSource(file.readText())
    }
}

// The project directory, wherever Gradle runs the tests from.
val projectDir: File =
    generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

// The app's Kotlin sources in every source set that is not a test, under both kotlin/ and java/.
fun appSources(): List<File> =
    File(projectDir, "app/src")
        .listFiles()
        .orEmpty()
        .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
        .flatMap { set -> listOf(File(set, "kotlin"), File(set, "java")) }
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" }.toList() }
        .sorted()

package io.github.kauri_off.kiroku.rules

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// A number: digits or a %d argument; in a plural item, whose arguments are counts, a %s argument too, which carries
// a count formatted for the locale.
private fun number(plural: Boolean) = if (plural) """(\d|%(\d+\$)?[ds])""" else """(\d|%(\d+\$)?d)"""

// Strings whose number is labelled by the word before it ("Episode 13 watched"): the word after is not the number's,
// so the space after the number may break.
private val labelledBefore = setOf("list_episode_watched")

// An ordinary space between a number and the word, slash or colon next to it, where a line could break.
fun numberSpaceProblems(
    name: String,
    text: String,
    plural: Boolean = false,
): List<String> {
    val numberEnd = Regex(number(plural) + "$")
    val joinedBefore = Regex("""\p{L}\u00A0~?""" + number(plural) + "$")
    val numberStart = Regex("^~?" + number(plural))
    return text.indices
        .filter { i ->
            text[i] == ' ' &&
                i > 0 &&
                i < text.length - 1 &&
                run {
                    val before = text.substring(0, i)
                    val after = text.substring(i + 1)
                    val joinsWord = { c: Char -> c.isLetter() || c == '/' }
                    (
                        numberEnd.containsMatchIn(before) &&
                            !(name.substringAfterLast(' ') in labelledBefore && joinedBefore.containsMatchIn(before)) &&
                            joinsWord(after[0])
                    ) ||
                        (numberStart.containsMatchIn(after) && (joinsWord(before.last()) || before.last() == ':'))
                }
        }.map { "$name: a breaking space at ${it + 1} in \"$text\"; use \\u00A0" }
}

data class StringResource(
    val name: String,
    val text: String,
    val plural: Boolean,
)

// Every string and plural item of the app's resources, as the app reads it.
fun stringResources(): List<StringResource> =
    File(projectDir, "app/src/main/res")
        .walk()
        .filter { it.isFile && it.parentFile.name.startsWith("values") && it.name.startsWith("strings") }
        .sorted()
        .flatMap { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            listOf("string", "item").flatMap { tag ->
                val nodes = document.getElementsByTagName(tag)
                (0 until nodes.length).map { index ->
                    val node = nodes.item(index)
                    val key =
                        node.attributes.getNamedItem("name")?.nodeValue ?: node.parentNode.attributes
                            .getNamedItem("name")
                            .nodeValue
                    StringResource("${file.parentFile.name}/${file.name} $key", unescape(node.textContent), tag == "item")
                }
            }
        }.toList()

private fun unescape(raw: String): String =
    Regex("""\\u([0-9a-fA-F]{4})""").replace(raw.trim('"')) {
        it.groupValues[1]
            .toInt(16)
            .toChar()
            .toString()
    }

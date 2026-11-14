package io.github.kauri_off.kiroku.data.recorder

import java.io.File

// Brings stored answers up to the sanitiser's rules without asking MAL; the directory is the argument.
fun main(arguments: Array<String>) {
    val changed = sanitiseStored(File(arguments.single()))
    changed.forEach { println("$it: changed") }
    println("Stored answers changed: ${changed.size}")
}

// The names of the files that were not as the sanitiser leaves them.
internal fun sanitiseStored(directory: File): List<String> {
    val sanitiser = Sanitiser(emptyMap())
    return directory
        .listFiles { file -> file.isFile }
        .orEmpty()
        .sortedBy { it.name }
        .filter { file ->
            val stored = file.readText()
            val cleaned = sanitiser.json(stored) ?: stored
            if (cleaned != stored) file.writeText(cleaned)
            cleaned != stored
        }.map { it.name }
}

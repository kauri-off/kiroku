package io.github.kauri_off.kiroku.design.component

// A title as shown: it may break after each hyphen, so a hyphenated word never breaks inside a segment.
fun shownTitle(title: String): String = title.replace(HYPHEN, HYPHEN + BREAK)

private const val HYPHEN = "-"
private const val BREAK = "​"

package io.github.kauri_off.kiroku

// Marks a string literal no user reads: an id, key, route, URL or log tag. The string test accepts no other literal.
fun notShown(value: String): String = value

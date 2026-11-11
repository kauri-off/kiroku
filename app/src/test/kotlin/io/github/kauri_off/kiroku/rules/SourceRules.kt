package io.github.kauri_off.kiroku.rules

// User interface code is a file that uses Compose. In it, a string literal with a letter is a user-visible string
// outside the resources, except inside an annotation.
fun stringLiteralProblems(
    name: String,
    source: String,
): List<String> {
    if (!source.contains("import androidx.compose")) return emptyList()
    val parsed = KotlinSource(source)
    val lines = parsed.code.lines()
    return parsed.literals
        .filter { literal -> literal.text.any(Char::isLetter) }
        .filterNot { lines[it.line - 1].trimStart().startsWith("@") }
        .map { "$name:${it.line}: string literal \"${it.text}\" in user interface code; use a string resource" }
}

// Durations, curves, springs and delays of animations come only from the theme's motion source.
private val motionLiterals =
    listOf(
        Regex("""\btween\s*[(<]"""),
        Regex("""\bspring\s*[(<]"""),
        Regex("""\bkeyframes\s*[{(<]"""),
        Regex("""\bkeyframesWithSpline\b"""),
        Regex("""\bsnap\s*[(<]"""),
        Regex("""\b(infiniteRepeatable|repeatable)\s*[(<]"""),
        Regex("""\bCubicBezierEasing\s*\("""),
        Regex("""\b\w*Easing\b"""),
        Regex("""\b(durationMillis|delayMillis|dampingRatio|stiffness|visibilityThreshold)\b"""),
        Regex("""\bSpring\.\w+"""),
        Regex("""\bdelay\s*\("""),
        Regex("""\bwithFrameNanos\b"""),
    )

const val MOTION_SOURCE = "design/theme/Motion.kt"

fun motionLiteralProblems(
    name: String,
    source: String,
): List<String> {
    if (name.endsWith(MOTION_SOURCE) || !source.contains("import androidx.compose")) return emptyList()
    val lines = KotlinSource(source).code.lines()
    return lines.flatMapIndexed { index, line ->
        motionLiterals
            .filter { it.containsMatchIn(line) && !line.trimStart().startsWith("import ") }
            .map { "$name:${index + 1}: animation value outside $MOTION_SOURCE: ${line.trim()}" }
    }
}

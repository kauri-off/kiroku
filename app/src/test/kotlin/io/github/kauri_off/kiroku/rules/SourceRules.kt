package io.github.kauri_off.kiroku.rules

// Calls whose string argument is never shown to the user.
private val unseenCalls = Regex("""(\btestTag|\bLog\.[a-z]+)\s*$""")
private val annotation = Regex("""@[\w.:]+\s*$""")

// Every app source is user interface code for this rule: a string literal with a letter is a user-visible string
// outside the resources, unless it is an annotation's argument or the argument of a call that is never shown.
fun stringLiteralProblems(
    name: String,
    source: String,
): List<String> {
    val parsed = KotlinSource(source)
    return parsed.literals
        .filter { literal -> literal.text.any(Char::isLetter) }
        .filterNot { literal ->
            val callee = parsed.code.substring(0, openParenthesis(parsed.code, literal.offset) ?: return@filterNot false)
            annotation.containsMatchIn(callee) || unseenCalls.containsMatchIn(callee)
        }.map { "$name:${it.line}: string literal \"${it.text}\" in user interface code; use a string resource" }
}

// The opening parenthesis of the call or annotation whose arguments contain [offset], if any.
private fun openParenthesis(
    code: String,
    offset: Int,
): Int? {
    var depth = 0
    for (i in offset - 1 downTo 0) {
        when (code[i]) {
            ')' -> depth++
            '(' -> if (depth == 0) return i else depth--
            '{', '}', ';' -> if (depth == 0) return null
        }
    }
    return null
}

// Durations, curves, springs and delays of animations come only from the theme's motion scheme and tokens.
private val motionLiterals =
    listOf(
        Regex("""(?i)\b(tween|spring|snap|keyframes|keyframesWithSpline|repeatable|infiniteRepeatable)(Spec)?\s*[(<{]"""),
        Regex("""(?i)\w*decay\w*\s*[(<]"""),
        Regex("""\bCubicBezierEasing\b|(?<!Motion\.)\b\w*Easing\b|\bEase\w*\b"""),
        Regex("""\b(durationMillis|delayMillis|dampingRatio|stiffness|visibilityThreshold)\b"""),
        Regex("""\bSpring\.\w+|\b(Stiffness|DampingRatio)\w+"""),
        Regex("""\bAnimationConstants\b|\bDefaultDurationMillis\b"""),
        Regex("""\bdelay\s*\("""),
        Regex("""\bwith(Infinite)?(Animation)?Frame(Nanos|Millis)\b"""),
    )

const val MOTION_SOURCE = "app/src/main/kotlin/io/github/kauri_off/kiroku/design/theme/Motion.kt"

fun motionLiteralProblems(
    name: String,
    source: String,
): List<String> {
    if (name == MOTION_SOURCE) return emptyList()
    val lines = KotlinSource(source).code.lines()
    return lines.flatMapIndexed { index, line ->
        motionLiterals
            .filter { it.containsMatchIn(line) && !line.trimStart().startsWith("import ") }
            .map { "$name:${index + 1}: animation value outside $MOTION_SOURCE: ${line.trim()}" }
    }
}

// Theme, typography, shape, motion, content colour and window code, none of which the screenshot harness may have.
private val themeOrWindowCode =
    Regex(
        """\b(Material(Expressive)?Theme\s*\(|\w*[cC]olorScheme\s*\(|Typography\s*\(|Shapes\s*\(|\w*MotionScheme\b|LocalContentColor|""" +
            """LocalTextStyle|KirokuTheme\s*\(|enableEdgeToEdge|setContent\s*[({]|SystemBarStyle|statusBarColor|""" +
            """navigationBarColor|isAppearanceLight\w*\s*=(?!=)|setDecorFitsSystemWindows|WindowInsetsControllerCompat)""",
    )

fun themeOrWindowProblems(
    name: String,
    source: String,
): List<String> =
    KotlinSource(source)
        .code
        .lines()
        .mapIndexedNotNull { index, line -> if (themeOrWindowCode.containsMatchIn(line)) "$name:${index + 1}: ${line.trim()}" else null }

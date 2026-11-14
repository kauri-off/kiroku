package io.github.kauri_off.kiroku.rules

import java.io.File

// The one mark for a string no user reads, and the calls whose arguments are never shown.
private val unseenCalls = Regex("""(\bnotShown|\btestTag|\bLog\.[a-z]+)\s*$""")
private val annotation = Regex("""@[\w.:]+\s*$""")

// Every app source is user interface code for this rule. Any string literal but "" is text outside the resources,
// with or without a letter, unless it is an annotation's argument or the argument of notShown(), testTag() or Log.
fun stringLiteralProblems(
    name: String,
    source: String,
): List<String> {
    val parsed = KotlinSource(source)
    return parsed.literals
        .filter { literal -> literal.text.isNotEmpty() || literal.templated }
        .filterNot { literal ->
            val callee = parsed.code.substring(0, openParenthesis(parsed.code, literal.offset) ?: return@filterNot false)
            annotation.containsMatchIn(callee) || unseenCalls.containsMatchIn(callee)
        }.map {
            "$name:${it.line}: string literal \"${it.text}\" in user interface code; use a string resource, " +
                "or notShown(…) for a string no user reads"
        }
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

// The closing parenthesis that matches the one at [open].
private fun closeParenthesis(
    code: String,
    open: Int,
): Int {
    var depth = 0
    for (i in open until code.length) {
        when (code[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return i
        }
    }
    return code.length
}

// Durations, curves, springs and delays of animations come only from the theme's motion scheme: no source may write one.
private val motionLiterals =
    listOf(
        Regex("""(?i)\b(tween|spring|snap|keyframes|keyframesWithSpline|repeatable|infiniteRepeatable)(Spec)?\s*[(<{]"""),
        Regex("""(?i)\w*decay\w*\s*[(<]"""),
        Regex("""\b\w+Easing\b|\bEase\w*\b|\bEasing\s*\{"""),
        Regex("""\bSpring\.\w+|\b(Stiffness|DampingRatio)\w+"""),
        Regex("""\bAnimationConstants\b|\bDefaultDurationMillis\b"""),
        Regex("""\bwith(Infinite)?(Animation)?Frame(Nanos|Millis)\b"""),
    )

// Names that are animation values only in user interface code; elsewhere a delay is a debounce or a backoff.
private val interfaceMotionLiterals =
    listOf(
        Regex("""\b(durationMillis|delayMillis|dampingRatio|stiffness|visibilityThreshold)\b"""),
        Regex("""\bdelay\s*\("""),
    )

// User interface code: a file that declares a composable or uses Compose beyond its runtime.
private val interfaceCode = Regex("""@Composable\b|^import androidx\.compose\.(?!runtime\.)""", RegexOption.MULTILINE)

// Animation calls that fall back to Compose's own spec when their arguments name nothing from the motion scheme.
private val specCalls =
    Regex(
        """\b(animate(?!Scroll)\w*|Crossfade|AnimatedContent|AnimatedVisibility|fadeIn|fadeOut|slideIn\w*|slideOut\w*|""" +
            """expandIn|expandVertically|expandHorizontally|shrinkOut|shrinkVertically|shrinkHorizontally|scaleIn|scaleOut)""" +
            """\s*(<[^>()]*>)?\s*[({]""",
    )

private val fromScheme = Regex("""\bmotionScheme\.\w+Spec\b""")

// The expressive scheme's spatial springs overshoot; the app's own animations use its effects springs, which cannot.
private val spatialSpec = Regex("""\b\w*SpatialSpec\b""")

// The pager's settle and tab slide, the one motion value the owner chose over the theme's scheme (ARCHITECTURE 8.5).
const val PAGE_MOTION_SOURCE = "app/src/main/kotlin/io/github/kauri_off/kiroku/design/theme/PageSettle.kt"

// That value's curve and its use, the only animation values its file may write.
val pageMotion = listOf("tween<Float>(millis, easing = decelerate)", "Easing { 1f - (1f - it) * (1f - it) }")

// The full-screen loading indicator's grace, the title page's patience: a wait before showing a state, not motion.
const val PATIENCE_SOURCE = "app/src/main/kotlin/io/github/kauri_off/kiroku/design/component/ScreenState.kt"

// That wait, the only delay its file may write.
const val PATIENCE_WAIT = "delay(PATIENCE)"

// A message's time, which the host runs so that a finger holding the message stops it: a wait, not motion.
const val MESSAGE_TIME_SOURCE = "app/src/main/kotlin/io/github/kauri_off/kiroku/design/component/ScreenSnackbarHost.kt"

// That wait, the only delay its file may write.
const val MESSAGE_TIME_WAIT = "delay(millis)"

private val allowances =
    mapOf(PAGE_MOTION_SOURCE to pageMotion, PATIENCE_SOURCE to listOf(PATIENCE_WAIT), MESSAGE_TIME_SOURCE to listOf(MESSAGE_TIME_WAIT))

fun motionLiteralProblems(
    name: String,
    source: String,
): List<String> {
    val parsed = KotlinSource(source).code
    val code = allowances[name].orEmpty().fold(parsed) { code, allowed -> code.replace(allowed, "") }
    val rules = if (interfaceCode.containsMatchIn(code)) motionLiterals + interfaceMotionLiterals else motionLiterals
    val lines = code.lines()
    val literal =
        lines.flatMapIndexed { index, line ->
            rules
                .filter { it.containsMatchIn(line) && !line.trimStart().startsWith("import ") }
                .map { "$name:${index + 1}: animation value outside the theme's motion scheme: ${line.trim()}" }
        }
    val spatial =
        lines.mapIndexedNotNull { index, line ->
            if (spatialSpec.containsMatchIn(line)) "$name:${index + 1}: a spatial spring, which overshoots: ${line.trim()}" else null
        }
    val defaults =
        specCalls
            .findAll(code)
            .filterNot { call ->
                val open = call.range.last
                code[open] == '(' && fromScheme.containsMatchIn(code.substring(open, closeParenthesis(code, open)))
            }.map { call ->
                val line = code.substring(0, call.range.first).count { it == '\n' }
                "$name:${line + 1}: ${call.groupValues[1]} without a spec from the theme's motion scheme: ${lines[line].trim()}"
            }
    return literal + spatial + defaults
}

// Every text field takes withoutAutofill() first in its modifier, so none is offered to autofill or a password manager.
private val textFieldCall = Regex("""(?<!fun )\b((Basic|Outlined)?(Secure)?TextField|InputField)\s*\(""")
private val autofillFirst = Regex("""\bmodifier\s*=\s*\w+\s*\.\s*withoutAutofill\s*\(\s*\)""")

fun textFields(source: String): Int = textFieldCall.findAll(KotlinSource(source).code).count()

fun autofillProblems(
    name: String,
    source: String,
): List<String> {
    val code = KotlinSource(source).code
    return textFieldCall
        .findAll(code)
        .filterNot { call -> autofillFirst.containsMatchIn(code.substring(call.range.last, closeParenthesis(code, call.range.last))) }
        .map { call ->
            val line = code.substring(0, call.range.first).count { it == '\n' }
            "$name:${line + 1}: ${call.groupValues[1]} without Modifier.withoutAutofill() first in its modifier"
        }.toList()
}

// The one file that may use Material 3 Expressive API that is still experimental.
const val EXPRESSIVE_OPT_IN_SOURCE = "app/src/main/kotlin/io/github/kauri_off/kiroku/design/component/ExpressiveOptIn.kt"

// A dp value written as a number, which only the theme may hold.
private val dpLiteral = Regex("""\b\d+(\.\d+)?f?\s*\.dp\b|\bDp\s*\(\s*\d""")

const val THEME_SOURCES = "app/src/main/kotlin/io/github/kauri_off/kiroku/design/theme/"

fun dpLiteralProblems(
    name: String,
    source: String,
): List<String> {
    if (name.startsWith(THEME_SOURCES)) return emptyList()
    return KotlinSource(source).code.lines().mapIndexedNotNull { index, line ->
        if (dpLiteral.containsMatchIn(line)) "$name:${index + 1}: dp value outside $THEME_SOURCES: ${line.trim()}" else null
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

// A character a reader cannot see in a diff: format characters and spaces other than the plain one, which source
// files write as escapes.
private fun invisible(c: Char): Boolean =
    Character.getType(c) == Character.FORMAT.toInt() ||
        (Character.isSpaceChar(c) && c != ' ') ||
        (Character.isISOControl(c) && c !in "\n\r\t")

fun invisibleCharacterProblems(
    name: String,
    text: String,
): List<String> =
    text.lines().flatMapIndexed { index, line ->
        line
            .filter(::invisible)
            .map { "$name:${index + 1}: invisible character U+%04X; write it as an escape".format(it.code) }
    }

// S1: sign-in is the browser's, so nothing that ships shows a web page itself.
private val webView = Regex("""\bandroid\.webkit\b|\bWebView\w*""")

fun webViewProblems(
    name: String,
    source: String,
): List<String> {
    val code = if (name.endsWith(".kt") || name.endsWith(".kts")) KotlinSource(source).code else source
    return code.lines().mapIndexedNotNull { index, line ->
        if (webView.containsMatchIn(line)) "$name:${index + 1}: a WebView: ${line.trim()}" else null
    }
}

// Every Kotlin, Gradle script and XML source of the project's modules.
fun projectSources(): List<File> =
    projectDir
        .listFiles()
        .orEmpty()
        .filter { File(it, "src").isDirectory || it.name == "gradle" }
        .flatMap { module ->
            module
                .walk()
                .onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension in setOf("kt", "kts", "xml", "toml") }
                .toList()
        }.sorted()

// A file that declares a view model: a class extending ViewModel or one of the app's own view model classes.
private val viewModelClass = Regex("""(\)|\bclass\s+\w+(\s*<[^>]*>)?)\s*:\s*[\w.]*ViewModel\s*(<[^>]*>)?\s*\(""")

fun declaresViewModel(source: String): Boolean = viewModelClass.containsMatchIn(KotlinSource(source).code)

// The shell's view model owns the list's writer and listens from its creation; no other view model may.
const val SHELL_VIEW_MODEL_SOURCE = "app/src/main/kotlin/io/github/kauri_off/kiroku/shell/ShellViewModel.kt"

// A view model is given ListSource, which cannot write; these names would give it the writer or the whole graph.
private val writerOrGraph = Regex("""\b\w*(Graph|ListRepository|ListEngine)\w*\b""")

private val initBlock = Regex("""\binit\s*\{""")

private val aliasOfWriterOrGraph = Regex("""\btypealias\s+\w+(\s*<[^>]*>)?\s*=\s*[\w.]*(AppGraph|ListRepository|ListEngine)\b""")

fun viewModelProblems(
    name: String,
    source: String,
): List<String> {
    val lines = KotlinSource(source).code.lines()
    val aliases =
        lines.mapIndexedNotNull { index, line ->
            if (aliasOfWriterOrGraph.containsMatchIn(
                    line,
                )
            ) {
                "$name:${index + 1}: an alias hides the list's writer or the graph: ${line.trim()}"
            } else {
                null
            }
        }
    if (name == SHELL_VIEW_MODEL_SOURCE || !declaresViewModel(source)) return aliases
    return aliases +
        lines.flatMapIndexed { index, line ->
            listOfNotNull(
                writerOrGraph.find(line)?.let { "$name:${index + 1}: a view model names ${it.value}: ${line.trim()}" },
                if (initBlock.containsMatchIn(line)) "$name:${index + 1}: a view model starts work in init: ${line.trim()}" else null,
            )
        }
}

// The one file that collects a flow into Compose state, for every route: see collectAsScreenState.
const val SCREEN_STATE_SOURCE = "app/src/main/kotlin/io/github/kauri_off/kiroku/ScreenState.kt"

private val directCollection = Regex("""\bcollectAsState(WithLifecycle)?\s*\(""")

fun collectionProblems(
    name: String,
    source: String,
): List<String> {
    if (name == SCREEN_STATE_SOURCE) return emptyList()
    return KotlinSource(source).code.lines().mapIndexedNotNull { index, line ->
        if (directCollection.containsMatchIn(line)) "$name:${index + 1}: use collectAsScreenState: ${line.trim()}" else null
    }
}

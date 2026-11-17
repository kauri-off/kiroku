package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.rules.KotlinSource
import io.github.kauri_off.kiroku.rules.projectDir
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// What the preview build does beyond release: debug seams, manifest and resources, its build type and its dependencies.
class PreviewBuildTest {
    // Each name a release fills in its own sources, and why preview keeps the debug build's.
    private val seams =
        mapOf(
            "debugTools" to "the scenario panel's tab, and the file the reports are kept in",
            "reportUnexpected" to "a report is kept for the debug menu and logged; it stops only a debuggable build",
            "requestLog" to "one log line per request, with no header, value or body",
            "otherSource" to "the demo and the sandbox, only when chosen in the debug menu",
            "standInPosters" to "posters of fixture titles, which only the demo and the sandbox show",
            "liveRecorder" to "the recorder, started only from the debug menu",
            "searchStandIn" to "fixture titles, on the demo and the sandbox only",
            "profileStandIn" to "the fixture user, on the demo and the sandbox only",
            "signInFailed" to "one log line on why a sign-in failed, which names no credential",
        )

    // Each component the debug manifest adds or changes, and why preview keeps it.
    private val components =
        mapOf(
            ".MainActivity" to "the launcher's shortcut to the debug menu",
            ".debugmenu.DebugMenuActivity" to "the debug menu",
            ".debugmenu.GalleryActivity" to "the component gallery, opened from the debug menu",
            ".scenario.ScenarioTile" to "the scenario panel's quick settings tile",
        )

    // Each build file line that names the preview build type or sits in a block of it, and why it is there.
    private val previewLines =
        mapOf(
            """app › create("preview") {""" to "the build type",
            """app › create("preview") { › initWith(getByName("release"))""" to "the rest is release's: shrunk, R8's rules, not debuggable",
            """app › create("preview") { › applicationIdSuffix = ".preview"""" to "installs beside the release and the debug build",
            """app › create("preview") { › manifestPlaceholders["appLabel"] = "@string/app_name_preview"""" to "its launcher label",
            """app › create("preview") { › manifestPlaceholders["debugMenuShortcuts"] = "@xml/debug_menu_shortcuts_preview"""" to
                "the launcher shortcut to the debug menu",
            """app › create("preview") { › oauthRedirect("io.github.kauri-off.kiroku.preview://oauth")""" to
                "its own redirect, registered with MAL",
            """app › create("preview") { › signingConfig = signingConfigs.getByName("debug")""" to
                "the shared debug key, so each checkout's build installs over the last",
            """app › create("preview") { › matchingFallbacks += "release"""" to "a library without a preview build gives its release",
            """app › create("preview") { › isProfileable = true""" to "frames are measured on it",
            """app › sourceSets.named("preview") {""" to "the debug menu's sources",
            """app › sourceSets.named("preview") { › kotlin.directories += "src/debug/kotlin"""" to "the debug sources, held by the seams",
            """app › sourceSets.named("preview") { › res.directories += "src/debug/res"""" to "the debug resources, held by their names",
            """app › sourceSets.named("preview") { › manifest.srcFile("src/debug/AndroidManifest.xml")""" to
                "the debug manifest, held by its components",
            """app › implementation(libs.compose.ui.tooling.preview)""" to "Compose's @Preview, in every build type",
            """app › "previewImplementation"(testFixtures(project(":core")))""" to "the demo's fixtures",
            """app › "preview" to "io.github.kauri-off.kiroku.preview://oauth",""" to "the check of its redirect",
            """app › val composePreview = "androidx.compose.ui.tooling.PreviewActivity"""" to "Compose's preview activity, debug only",
            """app › "preview" to setOf(launcherActivity, profileInstaller, scenarioTile),""" to "the check of what it exports",
            """app › "preview" to releaseComponents + debugMenu,""" to "the check of its components: release's and the debug menu's",
            """app › if (variant.buildType !in setOf("debug", "preview")) return@onVariants""" to
                "its build-info file and the check of its shortcut",
            """data › create("preview") {""" to "the build type the app's preview takes",
            """data › create("preview") { › initWith(getByName("release"))""" to "the rest is release's",
            """data › sourceSets.named("preview") {""" to "the sandbox's and the recorder's sources",
            """data › sourceSets.named("preview") { › kotlin.directories += "src/debug/kotlin"""" to
                "used only from the app's debug sources",
            """data › onVariants(selector().withBuildType("preview")) """ +
                """{ it.sources.assets?.addGeneratedSourceDirectory(sandboxAssets, SandboxAssets::output) }""" to
                "the sandbox's recorded answers",
        )

    // Each dependency scoped to one build type, and why.
    private val scopedDependencies =
        mapOf(
            "app › debugImplementation(libs.compose.ui.tooling)" to "Compose's preview tooling, debug only",
            """app › debugImplementation(testFixtures(project(":core")))""" to "the demo's fixtures",
            """app › "previewImplementation"(testFixtures(project(":core")))""" to "the demo's fixtures",
        )

    // The build types each module declares; a setting for all of them, or one made outside its block, is not allowed.
    private val buildTypes =
        listOf("app › debug {", "app › release {", """app › create("preview") {""", """data › create("preview") {""")

    private val declaration = Regex("""^(?:@\S+\s+)*(?:\w+\s+)*(?:val|var|fun|class|object|interface|typealias)\s+(?:\w+\.)?(\w+)""")

    @Test
    fun `every name the debug sources fill in place of a release's is listed, with why preview keeps it`() {
        val declared =
            projectDir
                .listFiles()
                .orEmpty()
                .map { File(it, "src/release") }
                .filter { it.isDirectory }
                .flatMap { release -> release.walk().filter { it.extension == "kt" }.toList() }
                .flatMap { file -> KotlinSource(file.readText()).code.lines().mapNotNull { declaration.find(it)?.groupValues?.get(1) } }
        assertEquals(seams.keys.sorted(), declared.sorted())
    }

    // A permission, a query or an attribute of the application would change the preview build beyond its components.
    @Test
    fun `the debug manifest holds an application alone, and nothing else for any build that is not release`() {
        val manifests = modules().flatMap { module -> listOf("debug", "preview").map { File(module, "src/$it/AndroidManifest.xml") } }
        assertEquals(listOf(debugManifest), manifests.filter { it.isFile })
        val root = debugRoot()
        assertEquals(listOf("xmlns:android"), root.attributes.let { a -> (0 until a.length).map { a.item(it).nodeName } })
        assertEquals(listOf("application"), root.elements().map { it.nodeName })
    }

    @Test
    fun `the debug manifest's application has no attribute, and its components are listed with why preview keeps them`() {
        val application = debugRoot().elements().single { it.nodeName == "application" }
        assertEquals(emptyList<String>(), application.attributes.let { a -> (0 until a.length).map { a.item(it).nodeName } })
        val named = application.elements().map { it.attributes.getNamedItem("android:name").nodeValue }
        assertEquals(components.keys.sorted(), named.sorted())
    }

    private val debugManifest = File(projectDir, "app/src/debug/AndroidManifest.xml")

    private fun debugRoot() =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(debugManifest)
            .documentElement

    @Test
    fun `every line of a build file about the preview build type is listed, with why`() {
        assertEquals(previewLines.keys.sorted(), buildLines().filter { it.second }.map { it.first }.sorted())
    }

    @Test
    fun `every dependency of one build type is listed, with why`() {
        val scoped = Regex("""^"?(debug|release|preview)[A-Z]\w*"?\s*\(""")
        val found = buildLines().map { it.first }.filter { scoped.containsMatchIn(it.substringAfterLast(" › ")) }
        assertEquals(scopedDependencies.keys.sorted(), found.sorted())
    }

    // So isDebuggable or any other setting reaches the preview build only through its own listed block.
    @Test
    fun `the build types are set only in their own blocks`() {
        val files = modules().map { File(it, "build.gradle.kts") }.filter { it.isFile }
        val outside = files.flatMap { it.readLines() }.filter { Regex("""\bbuildTypes\s*\.""").containsMatchIn(it) }
        assertEquals(emptyList<String>(), outside)
        assertEquals(buildTypes, buildLines(blockOf = "buildTypes {").map { it.first })
    }

    private fun modules() =
        projectDir
            .listFiles()
            .orEmpty()
            .filter { File(it, "src").isDirectory }
            .sortedBy { it.name }

    // Each code line with the preview blocks around it and whether it is about preview, or [blockOf]'s direct children.
    private fun buildLines(blockOf: String? = null): List<Pair<String, Boolean>> =
        modules().filter { File(it, "build.gradle.kts").isFile }.flatMap { module ->
            val lines = File(module, "build.gradle.kts").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
            val found = mutableListOf<Pair<String, Boolean>>()
            val open = ArrayDeque<Pair<String, Int>>()
            var depth = 0
            for (line in lines) {
                val about = mentionsPreview.containsMatchIn(line) || open.any { mentionsPreview.containsMatchIn(it.first) }
                val path = (listOf(module.name) + open.filter { mentionsPreview.containsMatchIn(it.first) }.map { it.first } + line)
                when {
                    line.startsWith("}") -> Unit
                    blockOf == null -> found += path.joinToString(" › ") to about
                    open.lastOrNull()?.let { it.first == blockOf && it.second == depth - 1 } == true && line != "}" ->
                        found += "${module.name} › $line" to about
                }
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (line.endsWith("{")) open.addLast(line to depth - 1)
                while (open.isNotEmpty() && open.last().second >= depth) open.removeLast()
            }
            found
        }

    private val mentionsPreview = Regex("""(?i)\bpreview""")

    private fun org.w3c.dom.Node.elements(): List<org.w3c.dom.Node> =
        (0 until childNodes.length).map { childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }

    // A debug resource of a name the app's own sources declare would change the preview build's look or text.
    @Test
    fun `the debug resources declare no name of the app's own`() {
        val debug = resourceNames(File(projectDir, "app/src/debug/res"))
        val main = resourceNames(File(projectDir, "app/src/main/res"))
        assertEquals(emptySet<String>(), debug intersect main)
    }

    private fun resourceNames(res: File): Set<String> =
        res
            .walk()
            .filter { it.isFile }
            .flatMap { file ->
                if (file.parentFile
                        ?.name
                        .orEmpty()
                        .startsWith("values")
                ) {
                    Regex("""\bname="([^"]+)"""").findAll(file.readText()).map { it.groupValues[1] }
                } else {
                    sequenceOf(
                        file.parentFile
                            ?.name
                            .orEmpty()
                            .substringBefore('-') + "/" + file.nameWithoutExtension,
                    )
                }
            }.toSet()
}

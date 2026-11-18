package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.rules.projectDir
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// What the preview build takes from debug: its manifest and resources, its build type and its dependencies.
class PreviewBuildTest {
    // Each component the debug manifest adds or changes, and why preview keeps it.
    private val components =
        mapOf(
            ".MainActivity" to "the launcher's shortcut to the debug menu",
            ".debugmenu.DebugMenuActivity" to "the debug menu",
            ".debugmenu.GalleryActivity" to "the component gallery, opened from the debug menu",
            ".scenario.ScenarioTile" to "the scenario panel's quick settings tile",
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
    fun `every dependency of one build type is listed, with why`() {
        val scoped = Regex("""^"?(debug|release|preview)[A-Z]\w*"?\s*\(""")
        val found = buildLines().filter { scoped.containsMatchIn(it.substringAfterLast(" › ")) }
        assertEquals(scopedDependencies.keys.sorted(), found.sorted())
    }

    // So isDebuggable or any other setting reaches the preview build only through its own listed block.
    @Test
    fun `the build types are set only in their own blocks`() {
        val files = modules().map { File(it, "build.gradle.kts") }.filter { it.isFile }
        val outside = files.flatMap { it.readLines() }.filter { Regex("""\bbuildTypes\s*\.""").containsMatchIn(it) }
        assertEquals(emptyList<String>(), outside)
        assertEquals(buildTypes, buildLines(blockOf = "buildTypes {"))
    }

    private fun modules() =
        projectDir
            .listFiles()
            .orEmpty()
            .filter { File(it, "src").isDirectory }
            .sortedBy { it.name }

    // Each code line with the preview blocks around it, or [blockOf]'s direct children.
    private fun buildLines(blockOf: String? = null): List<String> =
        modules().filter { File(it, "build.gradle.kts").isFile }.flatMap { module ->
            val lines = File(module, "build.gradle.kts").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
            val found = mutableListOf<String>()
            val open = ArrayDeque<Pair<String, Int>>()
            var depth = 0
            for (line in lines) {
                val path = (listOf(module.name) + open.filter { mentionsPreview.containsMatchIn(it.first) }.map { it.first } + line)
                when {
                    line.startsWith("}") -> Unit
                    blockOf == null -> found += path.joinToString(" › ")
                    open.lastOrNull()?.let { it.first == blockOf && it.second == depth - 1 } == true && line != "}" ->
                        found += "${module.name} › $line"
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

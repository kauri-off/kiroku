package io.github.kauri_off.kiroku.wiring

import io.github.kauri_off.kiroku.rules.KotlinSource
import io.github.kauri_off.kiroku.rules.projectDir
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// What the preview build does that the release build does not. Preview compiles the debug sources, which reach the
// app only through a name the release sources declare too, through the debug manifest's components and through resources.
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

    @Test
    fun `every component of the debug manifest is listed, with why preview keeps it`() {
        val manifest =
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                File(projectDir, "app/src/debug/AndroidManifest.xml"),
            )
        val application = manifest.getElementsByTagName("application").item(0).childNodes
        val named =
            (0 until application.length)
                .map { application.item(it) }
                .filter { it.attributes != null }
                .map { it.attributes.getNamedItem("android:name").nodeValue }
        assertEquals(components.keys.sorted(), named.sorted())
    }

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

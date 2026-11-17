import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

fun Provider<String>.present(): Provider<String> = map { it.trim() }.filter { it.isNotEmpty() }

val malClientId =
    providers
        .gradleProperty("kiroku.malClientId")
        .present()
        .orElse(providers.environmentVariable("MAL_CLIENT_ID").present())
        .orElse("")
        .get()

check(malClientId.matches(Regex("[A-Za-z0-9._-]*"))) {
    "The MAL client id may contain only letters, digits, '.', '_' and '-'"
}

// A build type's OAuth redirect as registered with MAL: a constant of its own, never made from the application id.
fun com.android.build.api.dsl.ApplicationBuildType.oauthRedirect(uri: String) {
    manifestPlaceholders["oauthScheme"] = uri.substringBefore("://")
    manifestPlaceholders["oauthHost"] = uri.substringAfter("://")
    buildConfigField("String", "OAUTH_REDIRECT", "\"$uri\"")
}

android {
    namespace = "io.github.kauri_off.kiroku"
    compileSdk {
        version =
            release(
                libs.versions.sdk.compile
                    .get()
                    .toInt(),
            ) {
                minorApiLevel =
                    libs.versions.sdk.compileMinor
                        .get()
                        .toInt()
            }
    }

    defaultConfig {
        applicationId = "io.github.kauri_off.kiroku"
        minSdk =
            libs.versions.sdk.min
                .get()
                .toInt()
        targetSdk =
            libs.versions.sdk.target
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["appLabel"] = "@string/app_name"
        buildConfigField("String", "MAL_CLIENT_ID", "\"$malClientId\"")
    }

    signingConfigs {
        getByName("debug") {
            val shared = providers.gradleProperty("kiroku.debugKeystore").orNull?.let(::file)
            if (shared?.isFile == true) {
                storeFile = shared
                storePassword = "android"
                keyAlias = "kirokudebug"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "@string/app_name_debug"
            manifestPlaceholders["debugMenuShortcuts"] = "@xml/debug_menu_shortcuts_debug"
            oauthRedirect("io.github.kauri-off.kiroku.debug://oauth")
        }
        release {
            oauthRedirect("io.github.kauri-off.kiroku://oauth")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        // The release build with the debug menu, profileable: the owner's channel, and the build frames are measured on.
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            manifestPlaceholders["appLabel"] = "@string/app_name_preview"
            manifestPlaceholders["debugMenuShortcuts"] = "@xml/debug_menu_shortcuts_preview"
            oauthRedirect("io.github.kauri-off.kiroku.preview://oauth")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isProfileable = true
        }
    }

    sourceSets.named("preview") {
        kotlin.directories += "src/debug/kotlin"
        res.directories += "src/debug/res"
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }

    // MAL's recorded answers, which the names of MAL's values are tested against.
    sourceSets.named("test") {
        resources.directories += "../data/src/test/resources/mal"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        error += setOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(testFixtures(project(":core")))
    "previewImplementation"(testFixtures(project(":core")))

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.accessibility.check)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.coil.test)
    testImplementation(libs.okhttp.mockwebserver3)
}

val screenshotsRecorded =
    objects.property<Boolean>().convention(
        providers.gradleProperty("roborazzi.test.record").map { it.toBoolean() }.orElse(false),
    )
gradle.taskGraph.whenReady {
    if (hasTask(":app:recordRoborazziDebug")) screenshotsRecorded.set(true)
}

tasks.withType<Test>().matching { it.name == "testDebugUnitTest" }.configureEach {
    val recorded = screenshotsRecorded
    val outputDir = layout.buildDirectory.dir("outputs/roborazzi")
    val intermediateDir = layout.buildDirectory.dir("intermediates/roborazzi")
    val pendingMarker = layout.buildDirectory.file("tmp/roborazzi/RENDERED_AT")
    val head = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText
    val status = providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText
    outputs.upToDateWhen { !recorded.get() }
    outputs.cacheIf { !recorded.get() }
    doFirst {
        if (recorded.get()) {
            outputDir.get().asFile.deleteRecursively()
            intermediateDir.get().asFile.deleteRecursively()
            val dirty = if (status.get().isBlank()) "" else " dirty"
            pendingMarker
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText(head.get().trim() + dirty + "\n")
        }
    }
    doLast {
        if (recorded.get()) {
            pendingMarker.get().asFile.copyTo(outputDir.get().file("RENDERED_AT").asFile, overwrite = true)
        }
    }
}

abstract class VerifyReleaseDependencies : DefaultTask() {
    @get:Input
    abstract val capabilities: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val leaked = capabilities.get().filter { it.endsWith("-test-fixtures") }
        check(leaked.isEmpty()) { "The release build must not depend on test fixtures: $leaked" }
        report.get().asFile.writeText(capabilities.get().sorted().joinToString("\n"))
    }
}

abstract class VerifyReleaseClasses : DefaultTask() {
    @get:Input
    abstract val forbidden: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ListProperty<RegularFile>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val directories: ListProperty<Directory>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val inJars =
            jars.get().flatMap { jar ->
                ZipFile(jar.asFile).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .map { "${jar.asFile.name}!${it.name}" }
                        .toList()
                }
            }
        val inDirectories =
            directories.get().flatMap { directory ->
                directory.asFile
                    .walk()
                    .filter { it.isFile }
                    .map { it.relativeTo(directory.asFile).invariantSeparatorsPath }
                    .toList()
            }
        val leaked = (inJars + inDirectories).filter { entry -> forbidden.get().any { entry.substringAfter('!').startsWith(it) } }
        check(leaked.isEmpty()) { "The release build must not contain demo or fixture classes: ${leaked.take(10)}" }
        report.get().asFile.writeText("${inJars.size + inDirectories.size} entries checked\n")
    }
}

// A release package holds no resource or manifest name the debug source set declares, and none of [debugEntries].
abstract class VerifyReleasePackage : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val debugResources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mainResources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val debugManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mainManifest: RegularFileProperty

    @get:Input
    abstract val namespace: Property<String>

    @get:Input
    abstract val debugEntries: SetProperty<String>

    // MAL's recorded answers, which the sandbox of the debug and preview builds holds: no entry may equal one.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val recordedAnswers: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val packages: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val debugOnlyResources = resourceNames(debugResources)
        val mainNames = manifestNames(mainManifest.get().asFile)
        val debugOnlyNames = manifestNames(debugManifest.get().asFile, withAnchors = false)
        val files = packages.files.flatMap { it.walk().filter { file -> file.extension == "apk" || file.extension == "aab" }.toList() }
        check(files.isNotEmpty()) { "No release package in ${packages.files}" }
        for (file in files) {
            val (resources, manifest) = ZipFile(file).use { if (file.extension == "apk") apkContents(it) else bundleContents(it) }
            check(resources.any { it in resourceNames(mainResources) } && manifest.containsAll(mainNames)) {
                "Cannot read the names in ${file.name}"
            }
            val entries =
                ZipFile(file).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .map { it.name.removePrefix("base/root/") }
                        .toSet()
                }
            val recorded = recordedAnswers.files.filter { it.isFile }.associateBy { sha256(it.readBytes()) }
            check(recorded.isNotEmpty()) { "No recorded answers to check ${file.name} against" }
            val answers =
                ZipFile(file).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && sha256(zip.getInputStream(it).readBytes()) in recorded }
                        .map { it.name }
                        .toList()
                }
            val leaked =
                (debugOnlyResources intersect resources) + (debugOnlyNames intersect manifest) + (debugEntries.get() intersect entries) +
                    answers
            check(leaked.isEmpty()) { "The release package ${file.name} holds what only the debug build declares: $leaked" }
        }
        report.get().asFile.writeText(
            "${files.map { it.name }} checked against $debugOnlyResources, $debugOnlyNames, ${debugEntries.get()} and " +
                "${recordedAnswers.files.size} recorded answers\n",
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    // A resource's name, from its file's name or from its element in a values file.
    private fun resourceNames(directories: ConfigurableFileCollection): Set<String> =
        directories.asFileTree.files.flatMapTo(mutableSetOf()) { file ->
            if (file.parentFile.name.substringBefore('-') != "values") {
                listOf(file.name.substringBefore('.'))
            } else {
                val root =
                    javax.xml.parsers.DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(file)
                        .documentElement
                (0 until root.childNodes.length).mapNotNull { (root.childNodes.item(it) as? org.w3c.dom.Element)?.getAttribute("name") }
            }
        }

    // Every android:name a manifest adds, a class in full; an anchor is a component named only to add to it.
    private fun manifestNames(
        manifest: File,
        withAnchors: Boolean = true,
    ): Set<String> {
        val document =
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(
                manifest,
            )
        val elements = document.getElementsByTagName("*")
        return (0 until elements.length).mapNotNullTo(mutableSetOf()) { index ->
            val element = elements.item(index) as org.w3c.dom.Element
            val anchor = element.tagName in COMPONENTS && element.attributes.length == 1
            val removed = element.getAttributeNS(TOOLS, "node") == "remove"
            element
                .getAttributeNS(ANDROID, "name")
                .takeIf { it.isNotEmpty() && !removed && (withAnchors || !anchor) }
                ?.let { if (it.startsWith('.')) namespace.get() + it else it }
        }
    }

    private fun apkContents(apk: ZipFile): Pair<Set<String>, Set<String>> {
        val table = apk.bytes("resources.arsc")
        val keys = mutableSetOf<String>()
        var chunk = table.u16(2)
        while (chunk < table.size) {
            if (table.u16(chunk) == RES_TABLE_PACKAGE) keys += table.strings(chunk + table.u32(chunk + PACKAGE_KEY_STRINGS))
            chunk += table.u32(chunk + 4)
        }
        val manifest = apk.bytes("AndroidManifest.xml")
        return keys to manifest.strings(manifest.u16(2)).toSet()
    }

    private fun bundleContents(bundle: ZipFile): Pair<Set<String>, Set<String>> {
        val table =
            com.android.aapt.Resources.ResourceTable
                .parseFrom(bundle.bytes("base/resources.pb"))
        val keys =
            table.packageList
                .flatMap { it.typeList }
                .flatMap { it.entryList }
                .mapTo(mutableSetOf()) { it.name }
        val manifest = mutableSetOf<String>()

        fun walk(node: com.android.aapt.Resources.XmlNode) {
            node.element.attributeList.forEach { manifest += it.value }
            node.element.childList.forEach(::walk)
        }
        walk(
            com.android.aapt.Resources.XmlNode
                .parseFrom(bundle.bytes("base/manifest/AndroidManifest.xml")),
        )
        return keys to manifest
    }

    private fun ZipFile.bytes(name: String): ByteArray =
        getInputStream(checkNotNull(getEntry(name)) { "No $name in ${this.name}" }).readBytes()

    private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)

    // The strings of a binary resource string pool, UTF-8 or UTF-16.
    private fun ByteArray.strings(pool: Int): List<String> {
        val utf8 = u32(pool + 16) and 0x100 != 0
        val start = pool + u32(pool + 20)
        return (0 until u32(pool + 8)).map { index ->
            var at = start + u32(pool + u16(pool + 2) + 4 * index)
            if (utf8) {
                at += if (this[at].toInt() and 0x80 != 0) 2 else 1
                val length =
                    if (this[at].toInt() and 0x80 !=
                        0
                    ) {
                        ((this[at].toInt() and 0x7f) shl 8) or (this[at + 1].toInt() and 0xff)
                    } else {
                        this[at].toInt()
                    }
                at += if (this[at].toInt() and 0x80 != 0) 2 else 1
                String(this, at, length, Charsets.UTF_8)
            } else {
                var length = u16(at)
                at += 2
                if (length and 0x8000 != 0) {
                    length = ((length and 0x7fff) shl 16) or u16(at)
                    at += 2
                }
                String(this, at, length * 2, Charsets.UTF_16LE)
            }
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val TOOLS = "http://schemas.android.com/tools"
        const val RES_TABLE_PACKAGE = 0x0200
        const val PACKAGE_KEY_STRINGS = 276
        val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider")
    }
}

fun ResolvedComponentResult.capabilities(): Set<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val names = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(this))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component)) continue
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
            dependency.resolvedVariant.capabilities.forEach { names += "${it.group}:${it.name}" }
            queue += dependency.selected
        }
    }
    return names
}

val verifyReleaseDependencies =
    tasks.register<VerifyReleaseDependencies>("verifyReleaseDependencies") {
        capabilities.set(
            configurations
                .named("releaseRuntimeClasspath")
                .flatMap { it.incoming.resolutionResult.rootComponent }
                .map { it.capabilities() },
        )
        report.set(layout.buildDirectory.file("reports/release-dependencies.txt"))
    }

val verifyReleaseClasses =
    tasks.register<VerifyReleaseClasses>("verifyReleaseClasses") {
        forbidden.set(
            listOf(
                "io/github/kauri_off/kiroku/core/demo/",
                "io/github/kauri_off/kiroku/core/fixtures/",
                "io/github/kauri_off/kiroku/core/list/InMemoryLocalList",
                "io/github/kauri_off/kiroku/standin/",
                "io/github/kauri_off/kiroku/scenario/",
                "io/github/kauri_off/kiroku/source/",
                "io/github/kauri_off/kiroku/debugmenu/",
                "io/github/kauri_off/kiroku/design/gallery/",
                "io/github/kauri_off/kiroku/recorder/",
                "io/github/kauri_off/kiroku/data/recorder/",
                "io/github/kauri_off/kiroku/data/sandbox/",
                "io/github/kauri_off/kiroku/sandbox/",
            ),
        )
        report.set(layout.buildDirectory.file("reports/release-classes.txt"))
    }

fun VerifyReleasePackage.againstDebugSourceSet(name: String) {
    debugResources.from("src/debug/res")
    mainResources.from("src/main/res")
    debugManifest.set(file("src/debug/AndroidManifest.xml"))
    mainManifest.set(file("src/main/AndroidManifest.xml"))
    namespace.set(android.namespace)
    debugEntries.add(WriteBuildInfo.FILE)
    recordedAnswers.from(fileTree(rootProject.file("data/src/test/resources/mal")))
    report.set(layout.buildDirectory.file("reports/release-$name.txt"))
}

val verifyReleaseApk = tasks.register<VerifyReleasePackage>("verifyReleaseApk") { againstDebugSourceSet("apk") }
val verifyReleaseBundle = tasks.register<VerifyReleasePackage>("verifyReleaseBundle") { againstDebugSourceSet("bundle") }

// The short hash of HEAD, followed by a hash of the uncommitted changes when there are any; empty without git.
abstract class GitTree : ValueSource<String, GitTree.Parameters> {
    interface Parameters : ValueSourceParameters {
        val directory: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String =
        try {
            val root = File(git("rev-parse", "--show-toplevel")).canonicalFile
            check(
                root ==
                    parameters.directory
                        .get()
                        .asFile.canonicalFile,
            ) { "Not the project's own repository" }
            val head = git("rev-parse", "--short", "HEAD")
            val changes = git("status", "--porcelain") + git("diff", "HEAD", "--binary")
            if (changes.isBlank()) head else "$head ${sha256(changes)}"
        } catch (_: Exception) {
            ""
        }

    private fun git(vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        exec.exec {
            workingDir = parameters.directory.get().asFile
            commandLine("git", *arguments)
            standardOutput = output
            errorOutput = OutputStream.nullOutputStream()
        }
        return output.toString(Charsets.UTF_8).trim()
    }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

// The commit and build time of a debug or preview package, as a file in its root; no code changes with a commit.
abstract class WriteBuildInfo : DefaultTask() {
    @get:Input
    abstract val tree: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun write() {
        val parts = tree.get().split(' ')
        output
            .get()
            .file(FILE)
            .asFile
            .writeText("commit=${parts[0]}\ndirty=${parts.size > 1}\nbuilt=${Instant.now()}\n")
    }

    companion object {
        const val FILE = "build-info.properties"
    }
}

val gitTree = providers.of(GitTree::class) { parameters.directory.set(rootProject.layout.projectDirectory) }

// Unit tests and lint ignore the stamp on their classpaths; packaging takes it as an input of its own (below).
normalization { runtimeClasspath { ignore(WriteBuildInfo.FILE) } }

// The launcher shortcut of a debug or preview package targets that package and no other.
abstract class VerifyShortcuts : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xml: DirectoryProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val factory =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
        val metaData = factory.newDocumentBuilder().parse(manifest.get().asFile).getElementsByTagName("meta-data")
        val shortcuts =
            (0 until metaData.length)
                .map { metaData.item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS(ANDROID, "name") == "android.app.shortcuts" }
                .getAttributeNS(ANDROID, "resource")
                .removePrefix("@xml/")
        val intents = factory.newDocumentBuilder().parse(xml.get().file("$shortcuts.xml").asFile).getElementsByTagName("intent")
        val targets = (0 until intents.length).map { (intents.item(it) as org.w3c.dom.Element).getAttributeNS(ANDROID, "targetPackage") }
        check(targets.isNotEmpty() && targets.all { it == applicationId.get() }) {
            "The shortcuts in $shortcuts.xml target $targets, not ${applicationId.get()}"
        }
        report.get().asFile.writeText("$shortcuts targets ${applicationId.get()}\n")
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}

// What a merged manifest gets wrong about the addresses it takes, its build type's redirect and MAL's pages; nothing when right.
object RedirectRule {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
    const val ACTIVITY = "io.github.kauri_off.kiroku.signin.RedirectActivity"
    const val LINK = "io.github.kauri_off.kiroku.link.LinkActivity"

    // True from Android 12, whose filters can match a title's and a profile's page exactly; false before.
    const val LINK_ENABLED = "@bool/links_claimed"
    const val VIEW = "android.intent.action.VIEW"
    const val DEFAULT = "android.intent.category.DEFAULT"
    const val BROWSABLE = "android.intent.category.BROWSABLE"
    private val COMPONENTS = listOf("activity", "activity-alias", "service", "receiver", "provider")

    // A title's or a profile's page on MAL from a browser: the forms `readLink` in :core opens, and no other page of MAL's.
    val linkData =
        listOf(
            mapOf("scheme" to "https"),
            mapOf("host" to "myanimelist.net"),
            mapOf("host" to "www.myanimelist.net"),
        ) +
            listOf("", "/", "/[^/]+", "/[^/]+/").map { mapOf("pathAdvancedPattern" to "/anime/[1-9][0-9]*$it") } +
            listOf("", "/").map { mapOf("pathAdvancedPattern" to "/profile/[^/]+$it") }

    // An intent filter: actions, categories, each data element's attributes, other children, and its own attributes.
    data class Filter(
        val actions: List<String>,
        val categories: Set<String>,
        val data: List<Map<String, String>>,
        val other: List<String> = emptyList(),
        val attributes: Map<String, String> = emptyMap(),
    )

    fun parse(manifest: File): org.w3c.dom.Document =
        javax.xml.parsers.DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)

    // Only a filter with data or the browser's category takes an address; the redirect's and the link's activities have one each.
    fun problems(
        manifest: org.w3c.dom.Document,
        redirect: String,
        alsoExported: Set<String>,
    ): List<String> {
        val components = COMPONENTS.flatMap { manifest.getElementsByTagName(it).elements() }
        val takers =
            components
                .filter { component ->
                    component.filters().any { BROWSABLE in it.categories || it.data.isNotEmpty() || it.other.isNotEmpty() }
                }.map { it.name() }
        val own = components.filter { it.name() == ACTIVITY }.flatMap { it.filters() }
        val browser =
            Filter(
                listOf(VIEW),
                setOf(DEFAULT, BROWSABLE),
                listOf(mapOf("scheme" to redirect.substringBefore("://"), "host" to redirect.substringAfter("://"))),
            )
        val link = components.filter { it.name() == LINK }
        val linkFilters = link.flatMap { it.filters() }
        val title = Filter(listOf(VIEW), setOf(DEFAULT, BROWSABLE), linkData)
        val exported = components.filter { it.getAttributeNS(ANDROID, "exported") == "true" }.mapTo(mutableSetOf()) { it.name() }
        val shut =
            components.any {
                it.name() == ACTIVITY && (it.getAttributeNS(ANDROID, "enabled") == "false" || it.hasAttributeNS(ANDROID, "permission"))
            }
        val linkOpen = link.all { it.getAttributeNS(ANDROID, "enabled") == LINK_ENABLED && !it.hasAttributeNS(ANDROID, "permission") }
        return listOfNotNull(
            "Addresses are taken by $takers, not by $ACTIVITY and $LINK alone".takeIf {
                takers.sorted() !=
                    listOf(
                        LINK,
                        ACTIVITY,
                    ).sorted()
            },
            "$ACTIVITY takes $own, not $redirect from a browser and nothing else".takeIf { own != listOf(browser) },
            "$ACTIVITY is disabled or asks for a permission, so no browser reaches it".takeIf { shut },
            "$LINK takes $linkFilters, not a title's or a profile's page on MAL from a browser and nothing else".takeIf {
                linkFilters != listOf(title)
            },
            "$LINK is not enabled by $LINK_ENABLED alone, or asks for a permission".takeIf { !linkOpen },
            "Other apps can start $exported, not ${alsoExported + ACTIVITY + LINK}".takeIf { exported != alsoExported + ACTIVITY + LINK },
        )
    }

    private fun org.w3c.dom.NodeList.elements() = (0 until length).map { item(it) as org.w3c.dom.Element }

    private fun org.w3c.dom.Element.name() = getAttributeNS(ANDROID, "name")

    private fun org.w3c.dom.Element.filters(): List<Filter> =
        getElementsByTagName("intent-filter").elements().map { filter ->
            val parts = filter.getElementsByTagName("*").elements().groupBy { it.tagName }
            Filter(
                actions = parts["action"].orEmpty().map { it.name() },
                categories = parts["category"].orEmpty().mapTo(mutableSetOf()) { it.name() },
                data =
                    parts["data"].orEmpty().map { data ->
                        (0 until data.attributes.length).map { data.attributes.item(it) }.associate { it.localName to it.nodeValue }
                    },
                other = (parts.keys - setOf("action", "category", "data")).sorted(),
                attributes =
                    filter.attributes.let { all ->
                        (0 until all.length).map(all::item).associate { it.localName to it.nodeValue }
                    },
            )
        }
}

// The redirect activity alone takes an address, the build type's redirect from a browser, and nothing else is newly exported.
abstract class VerifyRedirect : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val redirect: Property<String>

    @get:Input
    abstract val alsoExported: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val problems = RedirectRule.problems(RedirectRule.parse(manifest.get().asFile), redirect.get(), alsoExported.get())
        check(problems.isEmpty()) { problems.joinToString("\n") }
        report.get().asFile.writeText(
            "${RedirectRule.ACTIVITY} alone takes ${redirect.get()}, ${RedirectRule.LINK} a title's or a profile's page on MAL; " +
                "exported besides them: ${alsoExported.get()}\n",
        )
    }
}

// Holds the rule above to a manifest made wrong in one way at a time: each way has to be a problem.
abstract class VerifyRedirectRule : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val redirect: Property<String>

    @get:Input
    abstract val alsoExported: SetProperty<String>

    @get:Input
    abstract val launcher: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val scheme = redirect.get().substringBefore("://")
        val host = redirect.get().substringAfter("://")
        val android = RedirectRule.ANDROID
        val main: (org.w3c.dom.Document) -> org.w3c.dom.Element = { it.component("activity", launcher.get()) }
        val own: (org.w3c.dom.Document) -> org.w3c.dom.Element = { it.component("activity", RedirectRule.ACTIVITY) }
        val ownFilter: (
            org.w3c.dom.Document,
        ) -> org.w3c.dom.Element = { own(it).getElementsByTagName("intent-filter").item(0) as org.w3c.dom.Element }
        val ownData: (
            org.w3c.dom.Document,
        ) -> org.w3c.dom.Element = { ownFilter(it).getElementsByTagName("data").item(0) as org.w3c.dom.Element }
        val link: (org.w3c.dom.Document) -> org.w3c.dom.Element = { it.component("activity", RedirectRule.LINK) }
        val linkFilter: (
            org.w3c.dom.Document,
        ) -> org.w3c.dom.Element = { link(it).getElementsByTagName("intent-filter").item(0) as org.w3c.dom.Element }
        val wrong =
            mapOf<String, (org.w3c.dom.Document) -> Unit>(
                // Another taker of an address: the launcher is exported already, so only its filter is new.
                "the launcher takes the scheme by sspPrefix" to
                    { main(it).filter(listOf(mapOf("scheme" to scheme, "sspPrefix" to "//$host"))) },
                "the launcher takes a web address, not from a browser" to
                    { main(it).filter(listOf(mapOf("scheme" to "https", "host" to "myanimelist.net")), browsable = false) },
                "the launcher has the browser's category and no data" to { main(it).filter(emptyList()) },
                "the launcher's filter holds an element that is no data" to
                    { main(it).filter(emptyList(), browsable = false).appendChild(it.createElement("uri-relative-filter-group")) },
                "another component takes the scheme" to { it.added("activity-alias").filter(listOf(mapOf("scheme" to scheme))) },
                // The redirect activity's own filter is not exactly the redirect from a browser.
                "the redirect activity has a second filter" to
                    { own(it).filter(listOf(mapOf("scheme" to "https", "host" to "myanimelist.net"))) },
                "its filter has a second scheme" to { ownFilter(it).addData(mapOf("scheme" to "https")) },
                "its filter has another action" to { ownFilter(it).named("action", "android.intent.action.SEND") },
                "its filter has another category" to { ownFilter(it).named("category", "android.intent.category.LAUNCHER") },
                "its filter holds another element" to { ownFilter(it).appendChild(it.createElement("uri-relative-filter-group")) },
                "its filter lacks the browser's category" to { document ->
                    val categories = ownFilter(document).getElementsByTagName("category")
                    val browsable =
                        (0 until categories.length).map { categories.item(it) as org.w3c.dom.Element }.single {
                            it.name() ==
                                RedirectRule.BROWSABLE
                        }
                    browsable.parentNode.removeChild(browsable)
                },
                "its data has a path" to { ownData(it).setAttributeNS(android, "android:path", "/") },
                "its data has another host" to { ownData(it).setAttributeNS(android, "android:host", "${host}2") },
                "its data has another scheme" to { ownData(it).setAttributeNS(android, "android:scheme", "$scheme.x") },
                // No browser reaches the redirect activity.
                "the redirect activity is not exported" to { own(it).setAttributeNS(android, "android:exported", "false") },
                "the redirect activity is disabled" to { own(it).setAttributeNS(android, "android:enabled", "false") },
                "the redirect activity asks for a permission" to
                    { own(it).setAttributeNS(android, "android:permission", "android.permission.BIND_JOB_SERVICE") },
                "there is no redirect activity" to { own(it).let { activity -> activity.parentNode.removeChild(activity) } },
                // The link activity claims more of MAL's site, or claims it where a filter cannot match a page exactly.
                "the link takes a manga's page" to { linkFilter(it).addData(mapOf("pathAdvancedPattern" to "/manga/[1-9][0-9]*")) },
                "the link takes a title's sub-pages" to
                    { linkFilter(it).addData(mapOf("pathAdvancedPattern" to "/anime/[1-9][0-9]*/[^/]+/.*")) },
                "the link takes an id that is no number" to { linkFilter(it).addData(mapOf("pathAdvancedPattern" to "/anime/.*")) },
                "the link takes a profile's sub-pages" to { linkFilter(it).addData(mapOf("pathAdvancedPattern" to "/profile/[^/]+/.+")) },
                "the link takes a user's list" to { linkFilter(it).addData(mapOf("pathAdvancedPattern" to "/animelist/[^/]+")) },
                "the link takes a profile by prefix" to { linkFilter(it).addData(mapOf("pathPrefix" to "/profile/")) },
                "the link takes a path prefix" to { linkFilter(it).addData(mapOf("pathPrefix" to "/anime/")) },
                "the link takes every path" to { document -> linkFilter(document).removeData { "pathAdvancedPattern" in it } },
                "the link takes http" to { linkFilter(it).addData(mapOf("scheme" to "http")) },
                "the link takes another host" to { linkFilter(it).addData(mapOf("host" to "m.myanimelist.net")) },
                "the link takes any host" to { document -> linkFilter(document).removeData { "host" in it } },
                "the link takes a title's page not from a browser" to { document ->
                    val categories = linkFilter(document).getElementsByTagName("category")
                    val browsable =
                        (0 until categories.length).map { categories.item(it) as org.w3c.dom.Element }.single {
                            it.name() == RedirectRule.BROWSABLE
                        }
                    browsable.parentNode.removeChild(browsable)
                },
                "the link has a second filter" to { link(it).filter(listOf(mapOf("scheme" to "https", "host" to "myanimelist.net"))) },
                "the link asks for verification of a domain the app does not own" to
                    { linkFilter(it).setAttributeNS(android, "android:autoVerify", "true") },
                "the link's filter has a priority" to { linkFilter(it).setAttributeNS(android, "android:priority", "999") },
                "the redirect's filter asks for verification" to { ownFilter(it).setAttributeNS(android, "android:autoVerify", "true") },
                "the link is claimed before Android 12" to { link(it).setAttributeNS(android, "android:enabled", "true") },
                "the link asks for a permission" to
                    { link(it).setAttributeNS(android, "android:permission", "android.permission.BIND_JOB_SERVICE") },
                "the link is not exported" to { link(it).setAttributeNS(android, "android:exported", "false") },
                "there is no link activity" to { link(it).let { activity -> activity.parentNode.removeChild(activity) } },
                "the launcher takes a title's or a profile's page" to { main(it).filter(RedirectRule.linkData) },
                // Something else is newly open to other apps.
                "another activity is exported" to { it.added("activity").setAttributeNS(android, "android:exported", "true") },
                "another provider is exported" to { it.added("provider").setAttributeNS(android, "android:exported", "true") },
            )

        fun problems(change: (org.w3c.dom.Document) -> Unit): List<String> =
            RedirectRule.problems(RedirectRule.parse(manifest.get().asFile).also(change), redirect.get(), alsoExported.get())
        check(problems {}.isEmpty()) { "The manifest as it is: ${problems {}}" }
        val passed = wrong.filterValues { problems(it).isEmpty() }.keys
        check(passed.isEmpty()) { "The redirect check lets these wrong manifests pass: $passed" }
        report.get().asFile.writeText(wrong.keys.joinToString("\n", postfix = "\n"))
    }

    private fun org.w3c.dom.Element.name() = getAttributeNS(RedirectRule.ANDROID, "name")

    private fun org.w3c.dom.Document.component(
        kind: String,
        name: String,
    ): org.w3c.dom.Element {
        val all = getElementsByTagName(kind)
        return (0 until all.length).map { all.item(it) as org.w3c.dom.Element }.single { it.name() == name }
    }

    // A component of the test's own, not exported.
    private fun org.w3c.dom.Document.added(kind: String): org.w3c.dom.Element {
        val component = createElement(kind)
        component.setAttributeNS(RedirectRule.ANDROID, "android:name", "io.github.kauri_off.kiroku.Planted")
        component.setAttributeNS(RedirectRule.ANDROID, "android:exported", "false")
        getElementsByTagName("application").item(0).appendChild(component)
        return component
    }

    private fun org.w3c.dom.Element.named(
        tag: String,
        name: String,
    ) {
        appendChild(ownerDocument.createElement(tag).also { it.setAttributeNS(RedirectRule.ANDROID, "android:name", name) })
    }

    // Takes out of a filter each data element one of whose attributes [which] names.
    private fun org.w3c.dom.Element.removeData(which: (Set<String>) -> Boolean) {
        val data = getElementsByTagName("data")
        (0 until data.length)
            .map { data.item(it) as org.w3c.dom.Element }
            .filter { element ->
                which((0 until element.attributes.length).mapTo(mutableSetOf()) { element.attributes.item(it).localName })
            }.forEach { removeChild(it) }
    }

    private fun org.w3c.dom.Element.addData(attributes: Map<String, String>) {
        val data = ownerDocument.createElement("data")
        attributes.forEach { (name, value) -> data.setAttributeNS(RedirectRule.ANDROID, "android:$name", value) }
        appendChild(data)
    }

    // A filter for a view from a browser with these data elements.
    private fun org.w3c.dom.Element.filter(
        data: List<Map<String, String>>,
        browsable: Boolean = true,
    ): org.w3c.dom.Element {
        val filter = ownerDocument.createElement("intent-filter")
        appendChild(filter)
        filter.named("action", RedirectRule.VIEW)
        filter.named("category", RedirectRule.DEFAULT)
        if (browsable) filter.named("category", RedirectRule.BROWSABLE)
        data.forEach { filter.addData(it) }
        return filter
    }
}

// How a merged manifest's components, start-up initializers, permissions and queries differ from those its build type names.
object DeclarationRule {
    const val STARTUP = "androidx.startup.InitializationProvider"
    const val COMPONENTS = "Components"
    const val INITIALIZERS = "Start-up initializers"
    const val PERMISSIONS = "Permissions"
    const val QUERIES = "Queries"
    private val components = setOf("activity", "activity-alias", "service", "receiver", "provider")
    private val permissions = setOf("uses-permission", "uses-permission-sdk-23")

    // Where each kind is named in this file.
    private val lists =
        mapOf(
            COMPONENTS to "releaseComponents, debugMenu or declaredComponents",
            INITIALIZERS to "startUpInitializers",
            PERMISSIONS to "releasePermissions or declaredPermissions",
            QUERIES to "declaredQueries",
        )

    // Each problem starts with its kind; nothing when the manifest declares what [named] names, kind by kind.
    fun problems(
        manifest: org.w3c.dom.Document,
        named: Map<String, Set<String>>,
    ): List<String> {
        val found = found(manifest)
        return lists.flatMap { (kind, where) ->
            val declared = found.getValue(kind)
            val expected = named.getValue(kind)
            listOfNotNull(
                (declared - expected).takeIf { it.isNotEmpty() }?.let {
                    "$kind the build does not name: $it. Name them in $where in app/build.gradle.kts, or take them out with " +
                        "tools:node=\"remove\" in app/src/main/AndroidManifest.xml."
                },
                (expected - declared).takeIf { it.isNotEmpty() }?.let {
                    "$kind the build names that are not there: $it. Take them out of $where in app/build.gradle.kts."
                },
            )
        }
    }

    // Components and initializers by name, a permission of the package's own as ".NAME", a query by its whole element.
    private fun found(manifest: org.w3c.dom.Document): Map<String, Set<String>> {
        val root = manifest.documentElement
        val own = root.getAttribute("package") + "."
        val declared = root.children("application").flatMap { it.children() }.filter { it.tagName in components }
        return mapOf(
            COMPONENTS to declared.mapTo(mutableSetOf()) { it.name().ifEmpty { "<${it.tagName}> without a name" } },
            INITIALIZERS to
                declared.filter { it.name() == STARTUP }.flatMap { it.children("meta-data") }.mapTo(mutableSetOf()) { it.name() },
            PERMISSIONS to
                root.children().filter { it.tagName in permissions }.mapTo(mutableSetOf()) {
                    if (it.name().startsWith(own)) "." + it.name().removePrefix(own) else it.name()
                },
            QUERIES to root.children("queries").flatMap { it.children() }.mapTo(mutableSetOf()) { it.described() },
        )
    }

    private fun org.w3c.dom.Element.children(tag: String? = null): List<org.w3c.dom.Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? org.w3c.dom.Element }.filter { tag == null || it.tagName == tag }

    private fun org.w3c.dom.Element.described(): String {
        val attributes = (0 until attributes.length).map { attributes.item(it) }.map { "${it.localName}=${it.nodeValue}" }.sorted()
        return (listOf(tagName) + attributes + children().map { "(${it.described()})" }).joinToString(" ")
    }

    private fun org.w3c.dom.Element.name() = getAttributeNS(RedirectRule.ANDROID, "name")
}

// A dependency brings no component, start-up initializer, permission or query in unseen; the rule is held to manifests
// a library would make, each of which has to fail with its own kind alone.
abstract class VerifyDeclarations : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val components: SetProperty<String>

    @get:Input
    abstract val initializers: SetProperty<String>

    @get:Input
    abstract val permissions: SetProperty<String>

    @get:Input
    abstract val queries: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val named =
            mapOf(
                DeclarationRule.COMPONENTS to components.get(),
                DeclarationRule.INITIALIZERS to initializers.get(),
                DeclarationRule.PERMISSIONS to permissions.get(),
                DeclarationRule.QUERIES to queries.get(),
            )

        fun problems(change: (org.w3c.dom.Document) -> Unit): List<String> =
            DeclarationRule.problems(RedirectRule.parse(manifest.get().asFile).also(change), named)
        val found = problems {}
        check(found.isEmpty()) { found.joinToString("\n") }
        val component = components.get().first { it != DeclarationRule.STARTUP }
        val wrong =
            mapOf<String, Pair<String, (org.w3c.dom.Document) -> Unit>>(
                "a library adds an activity" to (DeclarationRule.COMPONENTS to { it.added("application", "activity") }),
                "a library adds an activity-alias" to (DeclarationRule.COMPONENTS to { it.added("application", "activity-alias") }),
                "a library adds a service" to (DeclarationRule.COMPONENTS to { it.added("application", "service") }),
                "a library adds a receiver" to (DeclarationRule.COMPONENTS to { it.added("application", "receiver") }),
                "a library adds a provider" to (DeclarationRule.COMPONENTS to { it.added("application", "provider") }),
                "a library adds a component without a name" to
                    (DeclarationRule.COMPONENTS to { it.added("application", "service").removeAttributeNS(RedirectRule.ANDROID, "name") }),
                "a named component is gone" to (DeclarationRule.COMPONENTS to { it.removed(component) }),
                "a library adds a start-up initializer" to
                    (DeclarationRule.INITIALIZERS to { it.startUp().appendChild(it.planted("meta-data")) }),
                "a named start-up initializer is gone" to
                    (DeclarationRule.INITIALIZERS to { it.removed(initializers.get().first()) }),
                "a library adds a permission" to (DeclarationRule.PERMISSIONS to { it.added("manifest", "uses-permission") }),
                "a library adds a permission from Android 6" to
                    (DeclarationRule.PERMISSIONS to { it.added("manifest", "uses-permission-sdk-23") }),
                "a named permission is gone" to (DeclarationRule.PERMISSIONS to { it.removed(permissions.get().first()) }),
                "a library adds a query for a package" to (DeclarationRule.QUERIES to { it.query().appendChild(it.planted("package")) }),
                "a library adds a query for a provider, which is no component" to
                    (
                        DeclarationRule.QUERIES to { document ->
                            val provider = document.createElement("provider")
                            provider.setAttributeNS(RedirectRule.ANDROID, "android:authorities", "io.github.kauri_off.kiroku.planted")
                            document.query().appendChild(provider)
                        }
                    ),
                "a named query is gone" to
                    (
                        DeclarationRule.QUERIES to { document ->
                            document.query().let { queries -> queries.removeChild(queries.getElementsByTagName("*").item(0)) }
                        }
                    ),
            )
        val missed =
            wrong.filter { (_, case) -> problems(case.second).map { it.substringBefore(" the build") } != listOf(case.first) }.keys
        check(missed.isEmpty()) { "The declaration check does not fail with the kind alone for these wrong manifests: $missed" }
        report.get().asFile.writeText(
            named.entries.joinToString("") { (kind, names) -> "$kind: ${names.sorted()}\n" } +
                "Fails when ${wrong.keys.joinToString(", ")}\n",
        )
    }

    private fun org.w3c.dom.Document.planted(tag: String): org.w3c.dom.Element =
        createElement(tag).also { it.setAttributeNS(RedirectRule.ANDROID, "android:name", "io.github.kauri_off.kiroku.Planted") }

    private fun org.w3c.dom.Document.added(
        parent: String,
        tag: String,
    ): org.w3c.dom.Element = planted(tag).also { getElementsByTagName(parent).item(0).appendChild(it) }

    // The first element of the manifest that names [name], taken out.
    private fun org.w3c.dom.Document.removed(name: String) {
        val all = getElementsByTagName("*")
        val element = (0 until all.length).map { all.item(it) as org.w3c.dom.Element }.first { it.name() == name }
        element.parentNode.removeChild(element)
    }

    private fun org.w3c.dom.Document.startUp(): org.w3c.dom.Element {
        val all = getElementsByTagName("provider")
        return (0 until all.length).map { all.item(it) as org.w3c.dom.Element }.single { it.name() == DeclarationRule.STARTUP }
    }

    private fun org.w3c.dom.Element.name() = getAttributeNS(RedirectRule.ANDROID, "name")

    private fun org.w3c.dom.Document.query(): org.w3c.dom.Element =
        getElementsByTagName("queries").item(0) as org.w3c.dom.Element? ?: added("manifest", "queries")
}

// Section 3.1's redirects as MAL has them, and what else each build type lets another app start.
val registeredRedirects =
    mapOf(
        "release" to "io.github.kauri-off.kiroku://oauth",
        "debug" to "io.github.kauri-off.kiroku.debug://oauth",
        "preview" to "io.github.kauri-off.kiroku.preview://oauth",
    )
val launcherActivity = "io.github.kauri_off.kiroku.MainActivity"
val scenarioTile = "io.github.kauri_off.kiroku.scenario.ScenarioTile"
val profileInstaller = "androidx.profileinstaller.ProfileInstallReceiver"
val composePreview = "androidx.compose.ui.tooling.PreviewActivity"
val exportedBesidesTheRedirect =
    mapOf(
        "release" to setOf(launcherActivity, profileInstaller),
        "debug" to setOf(launcherActivity, profileInstaller, scenarioTile, composePreview),
        "preview" to setOf(launcherActivity, profileInstaller, scenarioTile),
    )

// Every component of each build type's merged manifest, and what runs when the process starts: no initializer of the network.
val releaseComponents =
    setOf(
        launcherActivity,
        RedirectRule.ACTIVITY,
        RedirectRule.LINK,
        DeclarationRule.STARTUP,
        profileInstaller,
        "androidx.room.MultiInstanceInvalidationService",
        "io.github.kauri_off.kiroku.notify.MarkWatchedReceiver",
    )
val debugMenu =
    setOf(
        "io.github.kauri_off.kiroku.debugmenu.DebugMenuActivity",
        "io.github.kauri_off.kiroku.debugmenu.GalleryActivity",
        scenarioTile,
    )
val declaredComponents =
    mapOf(
        "release" to releaseComponents,
        "debug" to releaseComponents + debugMenu + composePreview,
        "preview" to releaseComponents + debugMenu,
    )
val startUpInitializers =
    setOf(
        "androidx.lifecycle.ProcessLifecycleInitializer",
        "androidx.profileinstaller.ProfileInstallerInitializer",
    )

// The network, whether the device is offline (section 2.3), notifications and androidx.core's guard of receivers registered unexported.
val releasePermissions =
    setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.POST_NOTIFICATIONS",
        ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )
val declaredPermissions =
    mapOf(
        "release" to releasePermissions,
        "debug" to releasePermissions,
        "preview" to releasePermissions,
    )

// The browsers that offer Custom Tabs, for the sign-in.
val declaredQueries = setOf("intent (action name=android.support.customtabs.action.CustomTabsService)")

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verified =
            tasks.register<VerifyRedirect>("verify${name}Redirect") {
                manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                redirect.set(registeredRedirects.getValue(variant.buildType!!))
                alsoExported.set(exportedBesidesTheRedirect.getValue(variant.buildType!!))
                report.set(layout.buildDirectory.file("reports/redirect-${variant.name}.txt"))
            }
        val declared =
            tasks.register<VerifyDeclarations>("verify${name}Declarations") {
                manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                components.set(declaredComponents.getValue(variant.buildType!!))
                initializers.set(startUpInitializers)
                permissions.set(declaredPermissions.getValue(variant.buildType!!))
                queries.set(declaredQueries)
                report.set(layout.buildDirectory.file("reports/declarations-${variant.name}.txt"))
            }
        tasks.named { it == "assemble$name" || it == "test" }.configureEach { dependsOn(verified, declared) }
        if (variant.buildType == "debug") {
            val rule =
                tasks.register<VerifyRedirectRule>("verifyRedirectRule") {
                    manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    redirect.set(registeredRedirects.getValue("debug"))
                    alsoExported.set(exportedBesidesTheRedirect.getValue("debug"))
                    launcher.set(launcherActivity)
                    report.set(layout.buildDirectory.file("reports/redirect-rule.txt"))
                }
            tasks.named { it == "assembleDebug" || it == "test" }.configureEach { dependsOn(rule) }
        }
    }
    onVariants { variant ->
        if (variant.buildType !in setOf("debug", "preview")) return@onVariants
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val buildInfo = tasks.register<WriteBuildInfo>("write${name}BuildInfo") { tree.set(gitTree) }
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.PROJECT)
            .use(buildInfo)
            .toAppend(ScopedArtifact.JAVA_RES, WriteBuildInfo::output)
        // Normalization hides the stamp from packaging's classpath input; a changed stamp here makes it package in full.
        tasks.named { it == "package$name" }.configureEach {
            inputs.files(buildInfo).withPropertyName("buildInfo").withPathSensitivity(PathSensitivity.NONE)
        }
        val shortcuts =
            tasks.register<VerifyShortcuts>("verify${name}Shortcuts") {
                manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                xml.set(layout.projectDirectory.dir("src/debug/res/xml"))
                applicationId.set(variant.applicationId)
                report.set(layout.buildDirectory.file("reports/shortcuts-${variant.name}.txt"))
            }
        tasks.named { it == "assemble$name" }.configureEach { dependsOn(shortcuts) }
    }
    onVariants(selector().withBuildType("release")) { variant ->
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.ALL)
            .use(verifyReleaseClasses)
            .toGet(ScopedArtifact.CLASSES, VerifyReleaseClasses::jars, VerifyReleaseClasses::directories)
        verifyReleaseApk.configure { packages.from(variant.artifacts.get(SingleArtifact.APK)) }
        verifyReleaseBundle.configure { packages.from(variant.artifacts.get(SingleArtifact.BUNDLE)) }
    }
}

tasks.named { it == "preReleaseBuild" || it == "test" }.configureEach { dependsOn(verifyReleaseDependencies) }
tasks.named { it == "packageRelease" || it == "packageReleaseBundle" || it == "test" }.configureEach {
    dependsOn(verifyReleaseClasses)
}
tasks.named { it == "packageRelease" }.configureEach { finalizedBy(verifyReleaseApk) }
tasks.named { it == "signReleaseBundle" }.configureEach { finalizedBy(verifyReleaseBundle) }
tasks.named { it == "assembleRelease" }.configureEach { dependsOn(verifyReleaseApk) }
tasks.named { it == "bundleRelease" }.configureEach { dependsOn(verifyReleaseBundle) }

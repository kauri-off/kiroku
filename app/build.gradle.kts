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
        // The release build's code on the demo, profileable: the owner's channel, and the build frames are measured on.
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
            val leaked =
                (debugOnlyResources intersect resources) + (debugOnlyNames intersect manifest) + (debugEntries.get() intersect entries)
            check(leaked.isEmpty()) { "The release package ${file.name} holds what only the debug build declares: $leaked" }
        }
        report.get().asFile.writeText(
            "${files.map { it.name }} checked against $debugOnlyResources, $debugOnlyNames and ${debugEntries.get()}\n",
        )
    }

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

    // Every android:name in a manifest, a class in full; an anchor is a component named only to add to it.
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
            element
                .getAttributeNS(ANDROID, "name")
                .takeIf { it.isNotEmpty() && (withAnchors || !anchor) }
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
                "io/github/kauri_off/kiroku/standin/",
                "io/github/kauri_off/kiroku/scenario/",
                "io/github/kauri_off/kiroku/debugmenu/",
                "io/github/kauri_off/kiroku/design/gallery/",
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

// One activity takes the redirect registered with MAL for the build type, and no component is newly open to other apps.
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
        val document =
            javax.xml.parsers.DocumentBuilderFactory
                .newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest.get().asFile)
        val components = COMPONENTS.flatMap { document.getElementsByTagName(it).elements() }
        val scheme = redirect.get().substringBefore("://")
        val takers = components.filter { component -> component.filters().any { filter -> filter.any { it.endsWith(" scheme=$scheme") } } }
        val filter =
            listOf(
                "action $VIEW",
                "category $DEFAULT",
                "category $BROWSABLE",
                "data host=${redirect.get().substringAfter("://")} scheme=$scheme",
            )
        check(takers.map { it.name() } == listOf(ACTIVITY) && takers.single().filters() == listOf(filter)) {
            "${redirect.get()} is taken by ${takers.map { it.name() to it.filters() }}, not by $ACTIVITY from a browser alone"
        }
        val exported = components.filter { it.getAttributeNS(ANDROID, "exported") == "true" }.mapTo(mutableSetOf()) { it.name() }
        check(exported == alsoExported.get() + ACTIVITY) {
            "Other apps can start $exported, not ${alsoExported.get() + ACTIVITY}"
        }
        report.get().asFile.writeText("$ACTIVITY takes ${redirect.get()}; exported: $exported\n")
    }

    private fun org.w3c.dom.NodeList.elements() = (0 until length).map { item(it) as org.w3c.dom.Element }

    private fun org.w3c.dom.Element.name() = getAttributeNS(ANDROID, "name")

    // Each intent filter as its actions, categories and data, in the manifest's order.
    private fun org.w3c.dom.Element.filters(): List<List<String>> =
        getElementsByTagName("intent-filter").elements().map { filter ->
            filter.getElementsByTagName("*").elements().map { part ->
                val attributes = (0 until part.attributes.length).map { part.attributes.item(it) }
                val values = attributes.map { if (part.tagName == "data") "${it.localName}=${it.nodeValue}" else it.nodeValue }
                "${part.tagName} ${values.sorted().joinToString(" ")}"
            }
        }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val ACTIVITY = "io.github.kauri_off.kiroku.signin.RedirectActivity"
        const val VIEW = "android.intent.action.VIEW"
        const val DEFAULT = "android.intent.category.DEFAULT"
        const val BROWSABLE = "android.intent.category.BROWSABLE"
        val COMPONENTS = listOf("activity", "activity-alias", "service", "receiver", "provider")
    }
}

// Section 3.1's redirects as MAL has them, and what else each build type lets another app start.
val registeredRedirects =
    mapOf(
        "release" to "io.github.kauri-off.kiroku://oauth",
        "debug" to "io.github.kauri-off.kiroku.debug://oauth",
        "preview" to "io.github.kauri-off.kiroku.preview://oauth",
    )
val launcher = "io.github.kauri_off.kiroku.MainActivity"
val scenarioTile = "io.github.kauri_off.kiroku.scenario.ScenarioTile"
val profileInstaller = "androidx.profileinstaller.ProfileInstallReceiver"
val composePreview = "androidx.compose.ui.tooling.PreviewActivity"
val exportedBesidesTheRedirect =
    mapOf(
        "release" to setOf(launcher, profileInstaller),
        "debug" to setOf(launcher, profileInstaller, scenarioTile, composePreview),
        "preview" to setOf(launcher, profileInstaller, scenarioTile),
    )

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val redirect =
            tasks.register<VerifyRedirect>("verify${name}Redirect") {
                manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                redirect.set(registeredRedirects.getValue(variant.buildType!!))
                alsoExported.set(exportedBesidesTheRedirect.getValue(variant.buildType!!))
                report.set(layout.buildDirectory.file("reports/redirect-${variant.name}.txt"))
            }
        tasks.named { it == "assemble$name" }.configureEach { dependsOn(redirect) }
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

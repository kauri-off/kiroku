plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    id("com.google.devtools.ksp")
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.kauri_off.kiroku.data"
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
        minSdk =
            libs.versions.sdk.min
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets.named("test") {
        assets.directories += "schemas"
    }

    // The app's preview build takes this module with the tools of its debug menu, as its debug build does.
    buildTypes {
        create("preview") {
            initWith(getByName("release"))
        }
    }

    sourceSets.named("preview") {
        kotlin.directories += "src/debug/kotlin"
    }

    lint {
        error += setOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
        warningsAsErrors = false
        abortOnError = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// An entity that changed without its schema file fails the build once: the file is then updated, to be committed.
tasks.named("copyRoomSchemas") {
    val schemas = layout.projectDirectory.dir("schemas").asFile
    val before =
        layout.buildDirectory
            .file("room/schemas-before")
            .get()
            .asFile
    val state = { directory: File ->
        directory
            .walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.path }
            .joinToString("\n") { "${it.relativeTo(directory)}\n${it.readText()}" }
    }
    doFirst {
        before.parentFile.mkdirs()
        before.writeText(state(schemas))
    }
    doLast {
        if (before.readText() != state(schemas)) {
            throw GradleException("The database's entities no longer match data/schemas. The files are updated now: commit them.")
        }
    }
}

// Section 11.1: the signed-in recorder's answers stay outside every checkout; the tests that need them read them there.
val ownerRecordings: String =
    providers
        .gradleProperty("kiroku.ownerRecordings")
        .orElse(providers.environmentVariable("KIROKU_OWNER_RECORDINGS"))
        .orElse(providers.systemProperty("user.home").map { "$it/.local/share/kiroku/owner-recordings" })
        .get()

tasks.withType<Test>().configureEach {
    systemProperty("kiroku.ownerRecordings", ownerRecordings)
    inputs.files(fileTree(ownerRecordings)).withPropertyName("ownerRecordings")
}

// The public answers whose titles the sandbox reads (SandboxTitles.Answers), copied into the debug and preview builds'
// assets and nothing else of the recorded ones.
abstract class SandboxAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val answers: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        val recorded = output.get().asFile.resolve("recorded")
        recorded.deleteRecursively()
        recorded.mkdirs()
        answers.files.forEach { it.copyTo(File(recorded, it.name)) }
    }
}

val sandboxAnswers =
    listOf(
        "title_airing",
        "title_film",
        "title_finished",
        "title_no_related",
        "title_not_yet_aired",
        "title_unknown_episode_count",
        "ranking_airing",
        "ranking_upcoming",
    )

val sandboxAssets =
    tasks.register<SandboxAssets>("sandboxAssets") {
        val files = sandboxAnswers.map { layout.projectDirectory.file("src/test/resources/mal/recorded/$it.200.json") }
        answers.from(files)
        doFirst { check(files.all { it.asFile.isFile }) { "A public answer the sandbox reads is missing" } }
    }

androidComponents {
    onVariants(selector().withBuildType("debug")) { it.sources.assets?.addGeneratedSourceDirectory(sandboxAssets, SandboxAssets::output) }
    onVariants(selector().withBuildType("preview")) { it.sources.assets?.addGeneratedSourceDirectory(sandboxAssets, SandboxAssets::output) }
}

// A schema exported in the same run reaches the unit tests' assets.
tasks.named { it.endsWith("UnitTestAssets") }.configureEach { mustRunAfter("copyRoomSchemas") }

dependencies {
    api(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.datastore.preferences)
    implementation(libs.browser)

    testImplementation(project(":core")) {
        capabilities { requireFeature("contract") }
    }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.room.testing)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Section 11.1: no task depends on it, and it runs only as the one task of a build.
tasks.register<JavaExec>("recordMalAnswers") {
    description =
        "Asks MAL the requests of ClientIdRequests and stores the answers. " +
        "The client id is the property kiroku.malClientId and nothing else."
    val named = gradle.startParameter.taskNames.map { it.substringAfterLast(':') } == listOf(name)
    val fromTheBuild =
        mapOf(
            "KIROKU_RECORD_CLIENT_ID" to providers.gradleProperty("kiroku.malClientId"),
            "KIROKU_RECORD_PUBLIC_USER" to providers.gradleProperty("kiroku.recordPublicUser"),
            "KIROKU_RECORD_PRIVATE_USER" to providers.gradleProperty("kiroku.recordPrivateUser"),
            "KIROKU_RECORD_ONLY" to providers.gradleProperty("kiroku.recordOnly"),
        )
    classpath(provider { tasks.named<Test>("testDebugUnitTest").get().classpath })
    mainClass = "io.github.kauri_off.kiroku.data.recorder.RecordMalKt"
    args(
        layout.projectDirectory
            .dir("src/test/resources/mal/recorded")
            .asFile.path,
        layout.buildDirectory
            .dir("recorded-run")
            .get()
            .asFile.path,
    )
    doFirst {
        check(named) { "recordMalAnswers calls MAL: it runs only as the one task of a build" }
        fromTheBuild.forEach { (name, value) -> (this as JavaExec).environment(name, value.getOrElse("")) }
    }
}

// Section 11.1: AniList's answers for M3; no task depends on it, and it runs only as the one task of a build.
tasks.register<JavaExec>("recordAniListAnswers") {
    description = "Asks AniList the requests of AniListRequests, one at a time, and stores the answers."
    val named = gradle.startParameter.taskNames.map { it.substringAfterLast(':') } == listOf(name)
    val only = providers.gradleProperty("kiroku.recordOnly")
    classpath(provider { tasks.named<Test>("testDebugUnitTest").get().classpath })
    mainClass = "io.github.kauri_off.kiroku.data.recorder.RecordAniListKt"
    args(
        layout.projectDirectory
            .dir("src/test/resources/anilist/recorded")
            .asFile.path,
        layout.buildDirectory
            .dir("anilist-run")
            .get()
            .asFile.path,
    )
    doFirst {
        check(named) { "recordAniListAnswers calls AniList: it runs only as the one task of a build" }
        (this as JavaExec).environment("KIROKU_RECORD_ONLY", only.getOrElse(""))
    }
}

// Applies the sanitiser again to answers that are stored, by default the recorded ones; it asks MAL nothing.
tasks.register<JavaExec>("sanitiseMalAnswers") {
    description = "Brings the stored answers of MAL up to the sanitiser's rules."
    classpath(provider { tasks.named<Test>("testDebugUnitTest").get().classpath })
    mainClass = "io.github.kauri_off.kiroku.data.recorder.SanitiseStoredKt"
    args(
        providers
            .gradleProperty("kiroku.recordedIn")
            .orElse("data/src/test/resources/mal/recorded")
            .map {
                rootProject.layout.projectDirectory
                    .dir(it)
                    .asFile.path
            }.get(),
    )
}

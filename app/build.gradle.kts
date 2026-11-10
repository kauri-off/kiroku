import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
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
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(testFixtures(project(":core")))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
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
        forbidden.set(listOf("io/github/kauri_off/kiroku/core/demo/", "io/github/kauri_off/kiroku/core/fixtures/"))
        report.set(layout.buildDirectory.file("reports/release-classes.txt"))
    }

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.artifacts
            .forScope(ScopedArtifacts.Scope.ALL)
            .use(verifyReleaseClasses)
            .toGet(ScopedArtifact.CLASSES, VerifyReleaseClasses::jars, VerifyReleaseClasses::directories)
    }
}

tasks.named { it == "preReleaseBuild" || it == "test" }.configureEach { dependsOn(verifyReleaseDependencies) }
tasks.named { it == "packageRelease" || it == "packageReleaseBundle" || it == "test" }.configureEach {
    dependsOn(verifyReleaseClasses)
}

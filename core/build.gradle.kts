import org.codehaus.mojo.animal_sniffer.ClassListBuilder
import org.codehaus.mojo.animal_sniffer.SignatureChecker
import org.codehaus.mojo.animal_sniffer.logging.Logger as SnifferLogger

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-test-fixtures`
}

// The suites a pair of ports must pass, for this module's tests and for the tests of :data.
val contract: SourceSet = sourceSets.create("contract")

java {
    registerFeature("contract") {
        usingSourceSet(contract)
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    "contractApi"(project(path))
    "contractApi"(testFixtures(project(path)))
    "contractApi"(libs.junit)
    "contractApi"(libs.kotlinx.coroutines.test)
    testImplementation(contract.output)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Lint does not see a JVM module, so the classes that ship are checked against the Android API of the minSdk.
abstract class CheckAndroidApi : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:Classpath
    abstract val libraries: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signature: ConfigurableFileCollection

    @get:Internal
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val errors = mutableListOf<String>()
        val log =
            object : SnifferLogger {
                override fun info(message: String) = Unit

                override fun info(
                    message: String,
                    t: Throwable?,
                ) = Unit

                override fun debug(message: String) = Unit

                override fun debug(
                    message: String,
                    t: Throwable?,
                ) = Unit

                override fun warn(message: String) {
                    errors += message
                }

                override fun warn(
                    message: String,
                    t: Throwable?,
                ) {
                    errors += message
                }

                override fun error(message: String) {
                    errors += message
                }

                override fun error(
                    message: String,
                    t: Throwable?,
                ) {
                    errors += message
                }
            }
        val known = ClassListBuilder(log).apply { process((classes + libraries).files.toTypedArray()) }.packages
        signature.singleFile.inputStream().use {
            SignatureChecker(it, known, log).apply {
                setSourcePath(sources.files.toList())
                setAnnotationTypes(emptyList())
                process(classes.files.toTypedArray())
            }
        }
        report.get().asFile.writeText(errors.joinToString("\n", postfix = "\n"))
        check(errors.isEmpty()) {
            "Calls missing from Android ${signature.singleFile.name}:\n" + errors.joinToString("\n")
        }
    }
}

val androidApiSignature = configurations.create("androidApiSignature")

dependencies {
    androidApiSignature.name(
        "com.toasttab.android:gummy-bears-api-${libs.versions.sdk.min.get()}:${libs.versions.gummy.bears.get()}@signature",
    )
}

val checkAndroidApi =
    tasks.register<CheckAndroidApi>("checkAndroidApi") {
        val shipped = listOf(sourceSets.main.get(), sourceSets.testFixtures.get())
        classes.from(shipped.map { it.output.classesDirs })
        libraries.from(shipped.map { it.runtimeClasspath - it.output })
        sources.from(shipped.map { it.allSource.sourceDirectories })
        signature.from(androidApiSignature)
        report.set(layout.buildDirectory.file("reports/android-api.txt"))
    }

tasks.named("test") { dependsOn(checkAndroidApi) }

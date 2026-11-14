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

    lint {
        error += setOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
        warningsAsErrors = false
        abortOnError = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
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

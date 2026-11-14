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

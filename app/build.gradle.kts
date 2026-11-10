plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
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

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.accessibility.check)
    testImplementation(libs.coil.compose)
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

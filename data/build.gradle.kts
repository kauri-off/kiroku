plugins {
    alias(libs.plugins.android.library)
    id("com.google.devtools.ksp")
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        error += setOf("MissingTranslation", "ExtraTranslation", "MissingQuantity")
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    api(project(":core"))
    testImplementation(project(":core")) {
        capabilities { requireCapability("kiroku:core-contract") }
    }
    testImplementation(libs.junit)
}

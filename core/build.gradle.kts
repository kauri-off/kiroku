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

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

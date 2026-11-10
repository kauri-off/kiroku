plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

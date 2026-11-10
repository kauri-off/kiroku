buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint)
}

abstract class TestSlot : BuildService<BuildServiceParameters.None>

val testSlot =
    gradle.sharedServices.registerIfAbsent("testSlot", TestSlot::class) {
        maxParallelUsages.set(1)
    }

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    tasks.withType<Test>().configureEach {
        usesService(testSlot)
        maxParallelForks = 1
        maxHeapSize = "1536m"
    }
}

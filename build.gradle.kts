import java.time.Duration

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
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.ktlint)
}

abstract class TestSlot : BuildService<BuildServiceParameters.None>

val testSlot =
    gradle.sharedServices.registerIfAbsent("testSlot", TestSlot::class) {
        maxParallelUsages.set(1)
    }

// One module's tests at a time where memory is shared with other jobs; a CI runner's memory is the job's own.
val oneTestTaskAtATime = providers.environmentVariable("GITHUB_ACTIONS").orNull != "true"

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    tasks.withType<Test>().configureEach {
        if (oneTestTaskAtATime) usesService(testSlot)
        maxParallelForks = 1
        maxHeapSize = "1536m"
        timeout = Duration.ofMinutes(10)
    }
}

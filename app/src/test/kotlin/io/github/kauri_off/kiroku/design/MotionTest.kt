package io.github.kauri_off.kiroku.design

import io.github.kauri_off.kiroku.design.theme.Motion
import org.junit.Assert.assertEquals
import org.junit.Test

// Motion's springs are Material's standard motion scheme, which Compose Material 3 1.4 keeps internal.
class MotionTest {
    private val scheme = Class.forName("androidx.compose.material3.MotionScheme")
    private val companion = scheme.getField("Companion").get(null)
    private val standard = companion.javaClass.getMethod("standard\$material3").invoke(companion)

    private fun spec(name: String): Any = scheme.getMethod(name).invoke(standard)

    @Test
    fun springsAreTheStandardScheme() {
        assertEquals(spec("fastSpatialSpec"), Motion.fastSpatial<Float>())
        assertEquals(spec("defaultSpatialSpec"), Motion.defaultSpatial<Float>())
        assertEquals(spec("slowSpatialSpec"), Motion.slowSpatial<Float>())
        assertEquals(spec("fastEffectsSpec"), Motion.fastEffects<Float>())
        assertEquals(spec("defaultEffectsSpec"), Motion.defaultEffects<Float>())
        assertEquals(spec("slowEffectsSpec"), Motion.slowEffects<Float>())
    }
}

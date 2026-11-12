package io.github.kauri_off.kiroku.debugmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BuildInfoTest {
    private fun parse(text: String) = BuildInfo.parse(text.reader())

    @Test
    fun `the stamp names the commit, whether it had uncommitted changes, and the build time`() {
        val clean = parse("commit=72e26f6\ndirty=false\nbuilt=2026-11-12T09:41:00Z\n")
        assertEquals(Commit("72e26f6", dirty = false), clean.commit)
        assertEquals(Instant.parse("2026-11-12T09:41:00Z"), clean.built)
        assertEquals(Commit("72e26f6", dirty = true), parse("commit=72e26f6\ndirty=true\n").commit)
    }

    @Test
    fun `a build without git or with an unreadable time names neither`() {
        val stamp = parse("commit=\ndirty=false\nbuilt=yesterday\n")
        assertNull(stamp.commit)
        assertNull(stamp.built)
    }
}

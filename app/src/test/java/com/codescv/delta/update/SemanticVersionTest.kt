package com.codescv.delta.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun parsesAndComparesCorrectly() {
        assertEquals(SemanticVersion(2, 0, 5), SemanticVersion.parse("v2.0.5"))
        assertEquals(SemanticVersion(2, 0, 5), SemanticVersion.parse(" V2.0.5 "))
        assertEquals(SemanticVersion(2, 1, 0), SemanticVersion.parse("2.1"))
        assertEquals(SemanticVersion(2, 0, 5, "DEBUG"), SemanticVersion.parse("2.0.5-DEBUG"))
        assertEquals(SemanticVersion(2, 0, 6, "rc.1"), SemanticVersion.parse("v2.0.6-rc.1+build.9"))
        assertEquals("2.0.5", SemanticVersion.stripDebugSuffix("2.0.5-DEBUG"))
        assertEquals("2.0.5", SemanticVersion.stripDebugSuffix("2.0.5"))

        val invalid = listOf("", "v", "2", "2.0.5-", "2.0.5.1", "latest", "2.0.0-beta..1", "99999999999.0.0", null)
        for (input in invalid) {
            assertNull("Expected null for: $input", SemanticVersion.parse(input))
        }
    }

    @Test
    fun isNewerVersionIsStrictAndFailSafe() {
        assertTrue(SemanticVersion.isNewerVersion("2.0.5", "v2.0.6"))
        assertTrue(SemanticVersion.isNewerVersion("2.0.5", "v2.10.0"))
        assertTrue(SemanticVersion.isNewerVersion("2.0.5-DEBUG", "v2.0.5"))
        assertTrue(SemanticVersion.isNewerVersion("2.0.6-rc.1", "v2.0.6"))
        assertTrue(SemanticVersion.isNewerVersion("2.0.6-rc.1", "v2.0.6-rc.2"))
        assertFalse(SemanticVersion.isNewerVersion("2.0.5", "v2.0.5"))
        assertFalse(SemanticVersion.isNewerVersion("2.0.5", "v2.0.4"))
        assertFalse(SemanticVersion.isNewerVersion("2.0.5", "nightly"))
        assertFalse(SemanticVersion.isNewerVersion("garbage", "v9.9.9"))
    }
}

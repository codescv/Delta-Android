package com.codescv.delta.update

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class AgentFeedbackHelperTest {
    @Test
    fun isValidEmailAcceptsStandardAddressesAndRejectsMalformedOrInjectedInputs() {
        val valid =
            listOf(
                "agent@example.com",
                "  agent@example.com  ",
                "dev.agent+delta@sub.example.org",
                "team_01@qa-box.example.net",
            )
        for (address in valid) {
            assertTrue("Expected valid email: $address", AgentFeedbackHelper.isValidEmail(address))
        }

        val longLocal = "a".repeat(65) + "@example.com"
        val longTotal = "a".repeat(60) + "@" + ("sub.".repeat(50)) + "example.com"
        val invalid =
            listOf(
                null,
                "",
                "   ",
                "agent",
                "@example.com",
                "agent@",
                "a@b@example.com",
                "agent@localhost",
                "agent@.example.com",
                "agent@example.com.",
                "agent@example..com",
                ".agent@example.com",
                "agent.@example.com",
                "ag..ent@example.com",
                "agent@-example.com",
                "agent@example-.com",
                "agent@example.c",
                "agent @example.com",
                "agent@example.com\nBcc:other@example.org",
                "agent@example.com\r\nSubject:injected",
                longLocal,
                longTotal,
            )
        for (address in invalid) {
            assertFalse("Expected invalid email: $address", AgentFeedbackHelper.isValidEmail(address))
        }
    }

    @Test
    fun formatSubjectUsesDeltaAndroidPrefixNormalizedVersionAndSingleLineSummary() {
        assertEquals(
            "delta-android: [v2.0.5] Virtual pad layout",
            AgentFeedbackHelper.formatSubject("Virtual pad layout", "2.0.5"),
        )
        assertEquals(
            "delta-android: [v2.0.5] Virtual pad layout",
            AgentFeedbackHelper.formatSubject("Virtual pad layout", "2.0.5-DEBUG"),
        )
        assertEquals(
            "delta-android: [v2.0.5] Multi line summary",
            AgentFeedbackHelper.formatSubject("  Multi\r\nline\nsummary  ", "v2.0.5"),
        )
        assertEquals(
            "delta-android: [v2.0.5] Feedback",
            AgentFeedbackHelper.formatSubject("   ", "2.0.5"),
        )
    }

    @Test
    fun resolveSummaryAndFormatEmailBodyHandleOptionalFields() {
        assertEquals(
            "Explicit summary",
            AgentFeedbackHelper.resolveSummary("  Explicit summary  ", "Detailed steps"),
        )
        assertEquals(
            "First detail line",
            AgentFeedbackHelper.resolveSummary("   ", "\n  First detail line  \nSecond line"),
        )
        val longLine = "x".repeat(80)
        val truncated = AgentFeedbackHelper.resolveSummary("", longLine)
        assertEquals(61, truncated.length)
        assertTrue(truncated.endsWith("…"))

        val body =
            AgentFeedbackHelper.formatEmailBody(
                summary = "Gamepad issue",
                details = "Line 1\r\nLine 2",
                versionName = "2.0.5-DEBUG",
                versionCode = 20005,
            )
        assertEquals("Line 1\nLine 2\n\n---\nApp: Delta-Android v2.0.5 (20005)", body)
    }

    @Test
    fun resolveSummaryFallsBackToImageNameWhenOnlyImageIsAttached() {
        assertEquals(
            "Image: screenshot.png",
            AgentFeedbackHelper.resolveSummary("  ", "\n  \n", attachmentName = " screenshot.png "),
        )
        assertEquals(
            "Explicit title",
            AgentFeedbackHelper.resolveSummary("Explicit title", "", attachmentName = "screenshot.png"),
        )
        assertEquals(
            "First detail line",
            AgentFeedbackHelper.resolveSummary("", "First detail line", attachmentName = "screenshot.png"),
        )
        assertEquals("", AgentFeedbackHelper.resolveSummary("", "", attachmentName = null))
        assertEquals("", AgentFeedbackHelper.resolveSummary("", "", attachmentName = "   "))

        val longName = "n".repeat(80) + ".png"
        val truncated = AgentFeedbackHelper.resolveSummary("", "", attachmentName = longName)
        assertEquals(61, truncated.length)
        assertTrue(truncated.startsWith("Image: "))
        assertTrue(truncated.endsWith("…"))

        assertEquals(
            "delta-android: [v2.0.6] Image: screenshot.png",
            AgentFeedbackHelper.formatSubject(
                AgentFeedbackHelper.resolveSummary("", "", attachmentName = "screenshot.png"),
                versionName = "2.0.6",
            ),
        )
    }

    @Test
    fun formatEmailBodyIncludesAttachmentLineBeforeAppMetadata() {
        val withDetails =
            AgentFeedbackHelper.formatEmailBody(
                summary = "Crash",
                details = "Steps\r\nhere",
                versionName = "2.0.6-DEBUG",
                versionCode = 20006,
                attachmentName = "crash.png",
            )
        assertEquals("Steps\nhere\n\nAttachment: crash.png\n\n---\nApp: Delta-Android v2.0.6 (20006)", withDetails)

        val imageOnlySummary = AgentFeedbackHelper.resolveSummary("", "", attachmentName = "shot.jpg")
        val imageOnly =
            AgentFeedbackHelper.formatEmailBody(
                summary = imageOnlySummary,
                details = "",
                versionName = "2.0.6",
                versionCode = 20006,
                attachmentName = "shot.jpg",
            )
        assertEquals("Image: shot.jpg\n\nAttachment: shot.jpg\n\n---\nApp: Delta-Android v2.0.6 (20006)", imageOnly)

        val injected =
            AgentFeedbackHelper.formatEmailBody(
                summary = "S",
                details = "",
                versionName = "2.0.6",
                versionCode = 20006,
                attachmentName = "a\r\nb.png",
            )
        assertEquals("S\n\nAttachment: a b.png\n\n---\nApp: Delta-Android v2.0.6 (20006)", injected)

        val withoutAttachment =
            AgentFeedbackHelper.formatEmailBody(
                summary = "S",
                details = "",
                versionName = "2.0.6",
                versionCode = 20006,
                attachmentName = null,
            )
        assertEquals("S\n\n---\nApp: Delta-Android v2.0.6 (20006)", withoutAttachment)
        assertEquals(
            withoutAttachment,
            AgentFeedbackHelper.formatEmailBody(summary = "S", details = "", versionName = "2.0.6", versionCode = 20006),
        )
    }

    @Test
    fun sanitizeAttachmentNameStripsPathsAndUnsafeCharsAndAddsExtension() {
        assertEquals("shot.png", AgentFeedbackHelper.sanitizeAttachmentName("shot.png", "jpg"))
        assertEquals("passwd", AgentFeedbackHelper.sanitizeAttachmentName("../../etc/passwd"))
        assertEquals("evil.png", AgentFeedbackHelper.sanitizeAttachmentName("C:\\dir\\evil.png"))
        assertEquals("a_b_c.png", AgentFeedbackHelper.sanitizeAttachmentName("a:b\nc", "png"))
        assertEquals("feedback_image.jpg", AgentFeedbackHelper.sanitizeAttachmentName(null, "jpg"))
        assertEquals("feedback_image", AgentFeedbackHelper.sanitizeAttachmentName("  ", null))
        assertEquals("feedback_image", AgentFeedbackHelper.sanitizeAttachmentName("..", "*"))
        val longName = AgentFeedbackHelper.sanitizeAttachmentName("x".repeat(300) + ".webp")
        assertEquals(100, longName.length)
        assertTrue(longName.endsWith(".webp"))
    }

    @Test
    fun buildMailtoUriPercentEncodesSubjectAndBodyWithPercent20AndUtf8() {
        val uriWithoutBody =
            AgentFeedbackHelper.buildMailtoUri(
                email = "  agent@example.com ",
                summary = "存档导出 & UI 反馈",
                body = "",
                versionName = "2.0.5",
            )
        assertTrue(uriWithoutBody.startsWith("mailto:agent@example.com?subject="))
        assertFalse("mailto query must encode spaces as %20, not +", uriWithoutBody.contains("+"))
        val rawSubjectParam = uriWithoutBody.substringAfter("?subject=")
        assertEquals(
            "delta-android: [v2.0.5] 存档导出 & UI 反馈",
            decodePercentOnly(rawSubjectParam),
        )

        val uriWithBody =
            AgentFeedbackHelper.buildMailtoUri(
                email = "agent@example.com",
                summary = "Audio crackle",
                body = "Steps:\r\n1. Launch SNES\n2. Enable fast-forward & check",
                versionName = "2.0.5-DEBUG",
            )
        assertFalse(uriWithBody.contains("+"))
        val query = uriWithBody.substringAfter("mailto:agent@example.com?")
        val params =
            query.split("&").associate { pair ->
                val idx = pair.indexOf('=')
                pair.substring(0, idx) to decodePercentOnly(pair.substring(idx + 1))
            }
        assertEquals("delta-android: [v2.0.5] Audio crackle", params["subject"])
        assertEquals("Steps:\n1. Launch SNES\n2. Enable fast-forward & check", params["body"])

        assertThrows(IllegalArgumentException::class.java) {
            AgentFeedbackHelper.buildMailtoUri(
                email = "invalid-email",
                summary = "Test",
                versionName = "2.0.5",
            )
        }
    }

    @Test
    fun sharedPreferencesStorageDefaultsToEmptyValidatesAndSupportsClear() {
        val prefs = InMemorySharedPreferences()
        assertEquals("", AgentFeedbackHelper.DEFAULT_EMAIL)
        assertEquals("agent@example.com", AgentFeedbackHelper.EMAIL_PLACEHOLDER)
        assertEquals("", AgentFeedbackHelper.getSavedEmail(prefs))

        assertTrue(AgentFeedbackHelper.saveEmail(prefs, "  agent@example.com  "))
        assertEquals("agent@example.com", AgentFeedbackHelper.getSavedEmail(prefs))

        assertFalse(AgentFeedbackHelper.saveEmail(prefs, "not-an-email"))
        assertEquals("agent@example.com", AgentFeedbackHelper.getSavedEmail(prefs))

        assertTrue(AgentFeedbackHelper.saveEmail(prefs, "   "))
        assertEquals("", AgentFeedbackHelper.getSavedEmail(prefs))
    }

    /** Decodes `%XX` sequences without treating literal `+` as a space (matching `android.net.Uri.decode`). */
    private fun decodePercentOnly(encoded: String): String =
        URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name())

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = if (map.containsKey(key)) map[key] as? String else defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = if (map.containsKey(key)) map[key] as? MutableSet<String> else defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = (map[key] as? Int) ?: defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = (map[key] as? Long) ?: defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = (map[key] as? Float) ?: defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = (map[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = EditorImpl()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class EditorImpl : SharedPreferences.Editor {
            private val staged = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(
                key: String?,
                value: String?,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) staged[key] = value
                }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) staged[key] = values?.toMutableSet()
                }

            override fun putInt(
                key: String?,
                value: Int,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) staged[key] = value
                }

            override fun putLong(
                key: String?,
                value: Long,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) staged[key] = value
                }

            override fun putFloat(
                key: String?,
                value: Float,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) staged[key] = value
                }

            override fun putBoolean(
                key: String?,
                value: Boolean,
            ): SharedPreferences.Editor =
                apply {
                    if (key != null) staged[key] = value
                }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply {
                    if (key != null) removals += key
                }

            override fun clear(): SharedPreferences.Editor =
                apply {
                    clearAll = true
                }

            override fun commit(): Boolean {
                if (clearAll) map.clear()
                removals.forEach { map.remove(it) }
                for ((k, v) in staged) {
                    if (v == null) map.remove(k) else map[k] = v
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}

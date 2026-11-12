package com.codescv.delta.update

import com.codescv.delta.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delta-specific feedback defaults on top of the ported Lemuroid helper tests. */
class DeltaFeedbackDefaultsTest {
    @Test
    fun subjectUsesDeltaAndroidPrefixAndBuildVersion() {
        assertEquals("delta-android", AgentFeedbackHelper.PROJECT_PREFIX)
        // Debug builds carry `-DEBUG`; the subject tag is always the release tag `v2.0.7`.
        assertEquals("delta-android: [v2.0.7] Skin flicker", AgentFeedbackHelper.formatSubject("Skin flicker"))
        assertEquals(
            "delta-android: [v2.0.7] Skin flicker",
            AgentFeedbackHelper.formatSubject("Skin flicker", BuildConfig.VERSION_NAME),
        )
    }

    @Test
    fun defaultEmailIsEmptyAndPlaceholderIsExampleDomain() {
        assertEquals("", AgentFeedbackHelper.DEFAULT_EMAIL)
        assertEquals("agent@example.com", AgentFeedbackHelper.EMAIL_PLACEHOLDER)
        assertFalse(AgentFeedbackHelper.isValidEmail(AgentFeedbackHelper.DEFAULT_EMAIL))
    }

    @Test
    fun mailtoUsesPercent20NeverPlus() {
        val uri =
            AgentFeedbackHelper.buildMailtoUri(
                email = "agent@example.com",
                summary = "Pause menu blur",
                body = "Open pause menu a b",
            )
        assertEquals(
            "mailto:agent@example.com?subject=delta-android%3A%20%5Bv2.0.7%5D%20Pause%20menu%20blur" +
                "&body=Open%20pause%20menu%20a%20b",
            uri,
        )
        assertFalse(uri.contains("+"))
        assertTrue(AgentFeedbackHelper.encodeMailtoParam("a+b c") == "a%2Bb%20c")
    }

    @Test
    fun bodyMetadataNamesDeltaAndroid() {
        val body = AgentFeedbackHelper.formatEmailBody("S", "", versionName = "2.0.5-DEBUG", versionCode = 20005)
        assertEquals("S\n\n---\nApp: Delta-Android v2.0.5 (20005)", body)
    }

    @Test
    fun attachmentSpecIsExplicitActionSendWithoutSelector() {
        val spec =
            FeedbackEmailTargetResolver.buildAttachmentSendSpec(
                recipient = "agent@example.com",
                subject = AgentFeedbackHelper.formatSubject("Crash"),
                text = "body",
                target = EmailComposeTarget("com.example.mail", "com.example.mail.Compose", "image/png"),
                attachmentUri = "content://com.codescv.delta.fileprovider/cache/feedback/a.png",
            )
        assertEquals("android.intent.action.SEND", spec.action)
        assertEquals("com.example.mail.Compose", spec.className)
        assertFalse(spec.hasSelector)
        assertEquals("delta-android: [v2.0.7] Crash", spec.subject)
    }
}

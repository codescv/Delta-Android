package com.codescv.delta.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackEmailTargetResolverTest {
    private val gmailCompose =
        ResolvedEmailActivity(
            FeedbackEmailTargetResolver.GMAIL_PACKAGE,
            "com.google.android.gm.ComposeActivityGmailExternal",
        )
    private val outlookCompose =
        ResolvedEmailActivity(
            "com.microsoft.office.outlook",
            "com.microsoft.office.outlook.compose.ComposeLauncherActivity",
        )
    private val qqMailCompose =
        ResolvedEmailActivity(
            "com.tencent.androidqqmail",
            "com.tencent.qqmail.launcher.desktop.LauncherActivity",
        )
    private val aospResolver = ResolvedEmailActivity("android", "com.android.internal.app.ResolverActivity")
    private val oplusResolver = ResolvedEmailActivity("com.oplus.resolver", "com.oplus.resolver.OplusResolverActivity")
    private val intentResolverChooser =
        ResolvedEmailActivity(
            "com.android.intentresolver",
            "com.android.intentresolver.ChooserActivity",
        )

    @Test
    fun isSystemResolverFiltersAndroidOplusIntentResolverAndResolverChooserActivities() {
        assertTrue(FeedbackEmailTargetResolver.isSystemResolver(aospResolver))
        assertTrue(FeedbackEmailTargetResolver.isSystemResolver(oplusResolver))
        assertTrue(FeedbackEmailTargetResolver.isSystemResolver(intentResolverChooser))
        assertTrue(
            FeedbackEmailTargetResolver.isSystemResolver(
                ResolvedEmailActivity("com.google.android.intentresolver", "x.Foo"),
            ),
        )
        assertTrue(
            FeedbackEmailTargetResolver.isSystemResolver(
                ResolvedEmailActivity("com.example.oem", "com.example.oem.CustomChooserActivity"),
            ),
        )
        assertTrue(FeedbackEmailTargetResolver.isSystemResolver(ResolvedEmailActivity(" ", "x")))
        assertFalse(FeedbackEmailTargetResolver.isSystemResolver(gmailCompose))
        assertFalse(FeedbackEmailTargetResolver.isSystemResolver(outlookCompose))
        assertFalse(FeedbackEmailTargetResolver.isSystemResolver(qqMailCompose))
    }

    @Test
    fun defaultEmailPackageIgnoresResolversAndUnverifiedOemSystemPackages() {
        val mailto = listOf(gmailCompose, outlookCompose)
        assertEquals(
            "com.microsoft.office.outlook",
            FeedbackEmailTargetResolver.defaultEmailPackage(outlookCompose, mailto),
        )
        assertNull(FeedbackEmailTargetResolver.defaultEmailPackage(null, mailto))
        assertNull(FeedbackEmailTargetResolver.defaultEmailPackage(aospResolver, mailto))
        assertNull(FeedbackEmailTargetResolver.defaultEmailPackage(oplusResolver, mailto))

        val oplusMail = ResolvedEmailActivity("com.oplus.mail", "com.oplus.mail.ComposeActivity")
        assertNull(FeedbackEmailTargetResolver.defaultEmailPackage(oplusMail, mailto))
        assertEquals(
            "com.oplus.mail",
            FeedbackEmailTargetResolver.defaultEmailPackage(oplusMail, mailto + oplusMail),
        )
    }

    @Test
    fun orderEmailPackagesPutsDefaultThenGmailThenOtherMailtoHandlers() {
        val mailto = listOf(qqMailCompose, aospResolver, outlookCompose, gmailCompose)
        assertEquals(
            listOf("com.microsoft.office.outlook", "com.google.android.gm", "com.tencent.androidqqmail"),
            FeedbackEmailTargetResolver.orderEmailPackages(outlookCompose, mailto),
        )
        // No concrete default (system resolver) -> Gmail first, then discovered handlers in query order.
        assertEquals(
            listOf("com.google.android.gm", "com.tencent.androidqqmail", "com.microsoft.office.outlook"),
            FeedbackEmailTargetResolver.orderEmailPackages(oplusResolver, mailto),
        )
        // Gmail is not probed when excluded and not discovered.
        assertEquals(
            listOf("com.tencent.androidqqmail"),
            FeedbackEmailTargetResolver.orderEmailPackages(
                defaultMailto = aospResolver,
                mailtoActivities = listOf(qqMailCompose, intentResolverChooser),
                includeGmailIfInstalled = false,
            ),
        )
    }

    @Test
    fun sendMimeCandidatesTryAttachmentMimeThenImageWildcardThenAnyThenRfc822() {
        assertEquals(
            listOf("image/png", "image/*", "*/*", "message/rfc822"),
            FeedbackEmailTargetResolver.sendMimeCandidates("image/png"),
        )
        assertEquals(
            listOf("image/*", "*/*", "message/rfc822"),
            FeedbackEmailTargetResolver.sendMimeCandidates(" "),
        )
        assertEquals(
            listOf("image/*", "*/*", "message/rfc822"),
            FeedbackEmailTargetResolver.sendMimeCandidates(null),
        )
    }

    @Test
    fun findSendActivityPrefersMailtoActivityWithinPackage() {
        val other =
            ResolvedEmailActivity(
                FeedbackEmailTargetResolver.GMAIL_PACKAGE,
                "com.google.android.gm.ComposeActivityGmail",
            )
        val target =
            FeedbackEmailTargetResolver.findSendActivity(
                packageName = FeedbackEmailTargetResolver.GMAIL_PACKAGE,
                attachmentMime = "image/jpeg",
                mailtoActivities = listOf(gmailCompose),
            ) { _, mime -> if (mime == "image/jpeg") listOf(other, gmailCompose) else emptyList() }
        assertEquals(
            EmailComposeTarget(FeedbackEmailTargetResolver.GMAIL_PACKAGE, gmailCompose.activityName, "image/jpeg"),
            target,
        )
    }

    @Test
    fun resolveComposeTargetsProbesOnlyEmailPackagesInPriorityOrder() {
        val queried = mutableListOf<Pair<String, String>>()
        val sendIndex =
            mapOf(
                (FeedbackEmailTargetResolver.GMAIL_PACKAGE to "image/jpeg") to listOf(gmailCompose),
                // Outlook only declares */* for ACTION_SEND.
                ("com.microsoft.office.outlook" to "*/*") to listOf(outlookCompose),
            )
        val targets =
            FeedbackEmailTargetResolver.resolveComposeTargets(
                defaultMailto = oplusResolver,
                mailtoActivities = listOf(outlookCompose, gmailCompose, aospResolver),
                attachmentMime = "image/jpeg",
            ) { pkg, mime ->
                queried += pkg to mime
                sendIndex[pkg to mime].orEmpty()
            }

        assertEquals(
            listOf(
                EmailComposeTarget(FeedbackEmailTargetResolver.GMAIL_PACKAGE, gmailCompose.activityName, "image/jpeg"),
                EmailComposeTarget("com.microsoft.office.outlook", outlookCompose.activityName, "image/jpeg"),
            ),
            targets,
        )
        assertTrue(queried.none { it.first == "android" || it.first.startsWith("com.oplus") })
    }

    @Test
    fun rfc822OnlyComposerKeepsRfc822TypeAndResolverHitsAreIgnored() {
        val pkg = "com.example.mail"
        val target =
            FeedbackEmailTargetResolver.findSendActivity(
                packageName = pkg,
                attachmentMime = "image/png",
                mailtoActivities = emptyList(),
            ) { p, mime ->
                when (mime) {
                    "image/png" -> listOf(ResolvedEmailActivity(p, "com.example.mail.ResolverActivity"))
                    "message/rfc822" ->
                        listOf(
                            ResolvedEmailActivity("other.pkg", "other.Compose"),
                            ResolvedEmailActivity(p, "com.example.mail.Compose"),
                        )
                    else -> emptyList()
                }
            }
        assertEquals(EmailComposeTarget(pkg, "com.example.mail.Compose", "message/rfc822"), target)
    }

    @Test
    fun uninstalledGmailWithoutSendActivityIsDropped() {
        val targets =
            FeedbackEmailTargetResolver.resolveComposeTargets(
                defaultMailto = null,
                mailtoActivities = listOf(qqMailCompose),
                attachmentMime = "image/jpeg",
            ) { pkg, _ -> if (pkg == qqMailCompose.packageName) listOf(qqMailCompose) else emptyList() }
        assertEquals(listOf(qqMailCompose.packageName), targets.map { it.packageName })
        assertTrue(
            FeedbackEmailTargetResolver.resolveComposeTargets(null, emptyList(), "image/jpeg") { _, _ -> emptyList() }
                .isEmpty(),
        )
    }

    @Test
    fun attachmentSendSpecIsExplicitActionSendWithExtrasGrantFlagAndNoSelector() {
        val uri = "content://com.codescv.delta.fileprovider/feedback/feedback_image.png"
        val subject = AgentFeedbackHelper.formatSubject(summary = "Crash on load", versionName = "1.17.6")
        val spec =
            FeedbackEmailTargetResolver.buildAttachmentSendSpec(
                recipient = "agent@example.com",
                subject = subject,
                text = "body",
                target =
                    EmailComposeTarget(
                        FeedbackEmailTargetResolver.GMAIL_PACKAGE,
                        gmailCompose.activityName,
                        "image/png",
                    ),
                attachmentUri = uri,
            )
        assertEquals("android.intent.action.SEND", spec.action)
        assertEquals("com.google.android.gm", spec.packageName)
        assertEquals("com.google.android.gm.ComposeActivityGmailExternal", spec.className)
        assertEquals("image/png", spec.mimeType)
        assertEquals(listOf("agent@example.com"), spec.recipients)
        assertEquals("delta-android: [v1.17.6] Crash on load", spec.subject)
        assertEquals("body", spec.text)
        assertEquals(uri, spec.streamUri)
        assertEquals("feedback_image", spec.clipLabel)
        assertTrue(spec.grantReadUriPermission)
        assertFalse(spec.hasSelector)
    }

    @Test
    fun blankActivityNameFallsBackToPackageScopedIntentAndBlankMimeToImageWildcard() {
        val spec =
            FeedbackEmailTargetResolver.buildAttachmentSendSpec(
                recipient = "agent@example.com",
                subject = "s",
                text = "t",
                target = EmailComposeTarget("com.example.mail", "", ""),
                attachmentUri = "content://x/y",
            )
        assertEquals("com.example.mail", spec.packageName)
        assertNull(spec.className)
        assertEquals("image/*", spec.mimeType)
        assertFalse(spec.hasSelector)
    }
}

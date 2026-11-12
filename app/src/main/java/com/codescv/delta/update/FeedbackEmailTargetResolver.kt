package com.codescv.delta.update

/** Pure-JVM view of an Android `ResolveInfo.activityInfo` (`packageName` + activity class `name`). */
data class ResolvedEmailActivity(
    val packageName: String,
    val activityName: String,
)

/** An email client's explicit `ACTION_SEND` compose activity, resolved for a specific MIME type. */
data class EmailComposeTarget(
    val packageName: String,
    val activityName: String,
    val mimeType: String,
)

/**
 * Pure-JVM description of the explicit `ACTION_SEND` intent used to open an email client's compose
 * screen with an image attachment. [AgentFeedbackHelper] maps this 1:1 onto an `android.content.Intent`.
 *
 * Invariant: [hasSelector] is always `false`. A `selector` (`ACTION_SENDTO mailto:`) on an
 * `ACTION_SEND image/...` intent is what made Android 15-17 (intent filter enforcement) reject the
 * launch with `MATCH_NO_TYPE` or ColorOS hijack it into the generic "分享1张图片" share sheet.
 */
data class FeedbackSendIntentSpec(
    val action: String,
    val packageName: String,
    val className: String?,
    val mimeType: String,
    val recipients: List<String>,
    val subject: String,
    val text: String,
    val streamUri: String,
    val clipLabel: String,
    val grantReadUriPermission: Boolean,
    val hasSelector: Boolean,
)

/**
 * Resolves installed email clients' `ACTION_SEND` compose activities so a feedback email with an
 * image attachment opens the email app's compose screen directly (explicit component, no selector)
 * instead of the system share sheet. Pure Kotlin so the ordering/filtering rules are unit-testable.
 */
object FeedbackEmailTargetResolver {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val GMAIL_PACKAGE = "com.google.android.gm"
    const val DEFAULT_IMAGE_MIME = "image/*"
    const val WILDCARD_MIME = "*/*"
    const val RFC822_MIME = "message/rfc822"
    const val CLIP_LABEL = "feedback_image"

    /** Packages that only ever host the system disambiguation/chooser UI, never an email client. */
    private val RESOLVER_PACKAGES =
        setOf(
            "android",
            "com.android.intentresolver",
            "com.google.android.intentresolver",
            "com.android.internal.app",
            "com.android.systemui",
            "com.oplus.resolver",
            "com.coloros.resolver",
            "com.heytap.resolver",
            "com.oneplus.resolver",
            "com.miui.resolver",
            "com.huawei.android.internal.app",
        )

    /** OEM system namespaces that may host resolvers; only trusted if they truly answer `mailto:`. */
    private val OEM_SYSTEM_PREFIXES =
        listOf(
            "com.android.internal.app",
            "com.oplus.",
            "com.coloros.",
            "com.heytap.",
        )

    /** Matches `ResolverActivity`, `OplusResolverActivity`, `ChooserActivity`, `ChooserActivityLauncher`, ... */
    private val RESOLVER_ACTIVITY_MARKERS = listOf("ResolverActivity", "ChooserActivity")

    fun isSystemResolver(activity: ResolvedEmailActivity): Boolean {
        val pkg = activity.packageName.trim()
        if (pkg.isEmpty()) return true
        if (pkg in RESOLVER_PACKAGES) return true
        if (pkg.endsWith(".resolver") || pkg.endsWith(".intentresolver")) return true
        val name = activity.activityName
        if (name.startsWith("com.android.internal.app.")) return true
        return RESOLVER_ACTIVITY_MARKERS.any { name.contains(it) }
    }

    private fun isOemSystemPackage(pkg: String): Boolean =
        OEM_SYSTEM_PREFIXES.any { pkg == it.trimEnd('.') || pkg.startsWith(it) }

    /**
     * Returns the user's default `mailto:` email package from `resolveActivity(..., MATCH_DEFAULT_ONLY)`,
     * or `null` when it resolved to a system resolver/chooser (no default chosen) or to an OEM system
     * package that is not itself a genuine `mailto:` handler in [mailtoActivities].
     */
    fun defaultEmailPackage(
        defaultMailto: ResolvedEmailActivity?,
        mailtoActivities: List<ResolvedEmailActivity>,
    ): String? {
        val candidate = defaultMailto ?: return null
        if (isSystemResolver(candidate)) return null
        val pkg = candidate.packageName.trim()
        if (isOemSystemPackage(pkg)) {
            val isRealHandler = mailtoActivities.any { it.packageName == pkg && !isSystemResolver(it) }
            if (!isRealHandler) return null
        }
        return pkg
    }

    /**
     * Deterministic candidate order: (1) user's default `mailto:` app (if a real email client),
     * (2) Gmail (probed even if not reported by the `mailto:` query; dropped later when it exposes no
     * `ACTION_SEND` activity), (3) the remaining discovered `mailto:` handlers in query order.
     */
    fun orderEmailPackages(
        defaultMailto: ResolvedEmailActivity?,
        mailtoActivities: List<ResolvedEmailActivity>,
        includeGmailIfInstalled: Boolean = true,
    ): List<String> {
        val ordered = LinkedHashSet<String>()
        defaultEmailPackage(defaultMailto, mailtoActivities)?.let { ordered += it }
        val discovered =
            mailtoActivities
                .filterNot { isSystemResolver(it) }
                .map { it.packageName.trim() }
                .filter { it.isNotEmpty() }
        if (GMAIL_PACKAGE in discovered || includeGmailIfInstalled) {
            ordered += GMAIL_PACKAGE
        }
        ordered += discovered
        return ordered.toList()
    }

    /** `ACTION_SEND` MIME types to try per package: attachment MIME (or [DEFAULT_IMAGE_MIME]), [DEFAULT_IMAGE_MIME], [WILDCARD_MIME], [RFC822_MIME]. */
    fun sendMimeCandidates(attachmentMime: String?): List<String> {
        val primary = attachmentMime?.trim().orEmpty().ifBlank { DEFAULT_IMAGE_MIME }
        return listOf(primary, DEFAULT_IMAGE_MIME, WILDCARD_MIME, RFC822_MIME).distinct()
    }

    /**
     * Finds [packageName]'s `ACTION_SEND` compose activity. [querySend] mirrors
     * `queryIntentActivities(Intent(ACTION_SEND).setType(mime).setPackage(pkg), ...)`. Within one MIME,
     * prefers the same activity that handles `mailto:` (e.g. Gmail's `ComposeActivityGmailExternal`).
     */
    fun findSendActivity(
        packageName: String,
        attachmentMime: String?,
        mailtoActivities: List<ResolvedEmailActivity>,
        querySend: (packageName: String, mimeType: String) -> List<ResolvedEmailActivity>,
    ): EmailComposeTarget? {
        val candidates = sendMimeCandidates(attachmentMime)
        val primaryMime = candidates.first()
        val mailtoNames =
            mailtoActivities
                .filter { it.packageName == packageName }
                .map { it.activityName }
                .toSet()
        for (mime in candidates) {
            val matches =
                querySend(packageName, mime)
                    .filter { it.packageName == packageName && !isSystemResolver(it) && it.activityName.isNotBlank() }
            if (matches.isEmpty()) continue
            val best = matches.firstOrNull { it.activityName in mailtoNames } ?: matches.first()
            // Deliver with the real attachment MIME when the activity was matched by a filter accepting it
            // (image/*, */*); keep message/rfc822 when that is the only filter the composer declares.
            return EmailComposeTarget(
                packageName = packageName,
                activityName = best.activityName,
                mimeType = if (mime == RFC822_MIME) mime else primaryMime,
            )
        }
        return null
    }

    fun resolveComposeTargets(
        defaultMailto: ResolvedEmailActivity?,
        mailtoActivities: List<ResolvedEmailActivity>,
        attachmentMime: String?,
        querySend: (packageName: String, mimeType: String) -> List<ResolvedEmailActivity>,
    ): List<EmailComposeTarget> =
        orderEmailPackages(defaultMailto, mailtoActivities)
            .mapNotNull { pkg -> findSendActivity(pkg, attachmentMime, mailtoActivities, querySend) }

    fun buildAttachmentSendSpec(
        recipient: String,
        subject: String,
        text: String,
        target: EmailComposeTarget,
        attachmentUri: String,
    ): FeedbackSendIntentSpec =
        FeedbackSendIntentSpec(
            action = ACTION_SEND,
            packageName = target.packageName,
            className = target.activityName.takeIf { it.isNotBlank() },
            mimeType = target.mimeType.ifBlank { DEFAULT_IMAGE_MIME },
            recipients = listOf(recipient),
            subject = subject,
            text = text,
            streamUri = attachmentUri,
            clipLabel = CLIP_LABEL,
            grantReadUriPermission = true,
            hasSelector = false,
        )
}

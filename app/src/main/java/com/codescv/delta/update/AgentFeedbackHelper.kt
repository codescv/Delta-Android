package com.codescv.delta.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.codescv.delta.BuildConfig
import com.codescv.delta.R
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Helper for locally persisted Agent feedback email configuration, email validation,
 * subject formatting, `mailto:` URI generation, and optional feedback image attachment staging.
 */
object AgentFeedbackHelper {
    /** A feedback image copied into `cacheDir/feedback/` and exposed through the app [FileProvider]. */
    data class StagedFeedbackImage(
        val file: File,
        val contentUri: Uri,
        val displayName: String,
        val mimeType: String,
    )

    const val PREF_KEY_AGENT_FEEDBACK_EMAIL = "agent_feedback_email"
    const val DEFAULT_EMAIL = ""
    const val EMAIL_PLACEHOLDER = "agent@example.com"
    const val PROJECT_PREFIX = "delta-android"

    private const val MAX_EMAIL_LENGTH = 254
    private const val MAX_LOCAL_PART_LENGTH = 64
    private const val MAX_FALLBACK_SUMMARY_LENGTH = 60
    private const val DEFAULT_SUMMARY_FALLBACK = "Feedback"
    private const val IMAGE_SUMMARY_PREFIX = "Image: "
    private const val MAX_ATTACHMENT_NAME_LENGTH = 100
    private const val DEFAULT_ATTACHMENT_BASENAME = "feedback_image"
    private const val DEFAULT_IMAGE_MIME_TYPE = "image/*"
    private const val FEEDBACK_CACHE_DIR = "feedback"
    private const val FEEDBACK_CLIP_LABEL = "feedback_image"
    private const val TAG = "AgentFeedbackHelper"

    private val LOCAL_PART_REGEX = Regex("^[A-Za-z0-9.!#\$%&'*+/=?^_`{|}~-]+\$")
    private val DOMAIN_LABEL_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val TLD_LABEL_REGEX = Regex("^[A-Za-z]{2,63}$")
    private val WHITESPACE_OR_CONTROL_REGEX = Regex("[\\s\\p{Cntrl}]")
    private val LINE_BREAK_RUN_REGEX = Regex("[\\r\\n]+")
    private val FILE_EXTENSION_REGEX = Regex("^[a-z0-9]{1,8}$")
    private val UNSAFE_FILENAME_CHARS_REGEX = Regex("[\\p{Cntrl}/\\\\:*?\"<>|]")

    fun normalizeEmail(raw: String?): String = raw?.trim().orEmpty()

    /**
     * Validates a standard single-recipient email address without relying on Android framework stubs.
     * Rejects blank addresses, header-injection control chars, missing TLD, and malformed labels.
     */
    fun isValidEmail(raw: String?): Boolean {
        val candidate = normalizeEmail(raw)
        if (candidate.isEmpty() || candidate.length > MAX_EMAIL_LENGTH) return false
        if (WHITESPACE_OR_CONTROL_REGEX.containsMatchIn(candidate)) return false

        val atIndex = candidate.indexOf('@')
        if (atIndex <= 0 || atIndex != candidate.lastIndexOf('@') || atIndex == candidate.lastIndex) {
            return false
        }

        val local = candidate.substring(0, atIndex)
        val domain = candidate.substring(atIndex + 1)
        if (local.length > MAX_LOCAL_PART_LENGTH) return false
        if (local.startsWith('.') || local.endsWith('.') || local.contains("..")) return false
        if (!LOCAL_PART_REGEX.matches(local)) return false

        if (domain.startsWith('.') || domain.endsWith('.') || domain.contains("..")) return false
        val labels = domain.split('.')
        if (labels.size < 2) return false
        if (!labels.all { DOMAIN_LABEL_REGEX.matches(it) }) return false
        return TLD_LABEL_REGEX.matches(labels.last())
    }

    /** Normalizes an app `versionName` (such as `2.0.5` or `2.0.5-DEBUG`) to a `v2.0.5` tag. */
    fun normalizeVersionTag(versionName: String): String {
        val stripped =
            SemanticVersion.stripDebugSuffix(versionName)
                .orEmpty()
                .removePrefix("v")
                .removePrefix("V")
                .trim()
        return if (stripped.isEmpty()) "v0.0.0" else "v$stripped"
    }

    /**
     * Resolves a single-line summary from explicit [summary] input or falls back to the first
     * non-blank line of [details] when [summary] is blank. When both are blank and an image
     * [attachmentName] is present, falls back to `Image: <name>`.
     */
    fun resolveSummary(
        summary: String,
        details: String = "",
        attachmentName: String? = null,
    ): String {
        val explicit = summary.trim().replace(LINE_BREAK_RUN_REGEX, " ").trim()
        if (explicit.isNotEmpty()) return explicit

        val firstDetailLine =
            details.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        val fallback =
            firstDetailLine.ifEmpty {
                val name = attachmentName?.replace(LINE_BREAK_RUN_REGEX, " ")?.trim().orEmpty()
                if (name.isEmpty()) "" else "$IMAGE_SUMMARY_PREFIX$name"
            }
        return if (fallback.length > MAX_FALLBACK_SUMMARY_LENGTH) {
            fallback.take(MAX_FALLBACK_SUMMARY_LENGTH).trimEnd() + "…"
        } else {
            fallback
        }
    }

    /** Formats the email subject with the `delta-android:` project prefix and normalized version tag. */
    fun formatSubject(
        summary: String,
        versionName: String = BuildConfig.VERSION_NAME,
    ): String {
        val tag = normalizeVersionTag(versionName)
        val cleanSummary =
            summary.trim()
                .replace(LINE_BREAK_RUN_REGEX, " ")
                .trim()
                .ifEmpty { DEFAULT_SUMMARY_FALLBACK }
        return "$PROJECT_PREFIX: [$tag] $cleanSummary"
    }

    /** Formats the email body combining user feedback details with app version metadata. */
    fun formatEmailBody(
        summary: String,
        details: String,
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        attachmentName: String? = null,
    ): String {
        val normalizedAttachment = attachmentName?.replace(LINE_BREAK_RUN_REGEX, " ")?.trim().orEmpty()
        val normalizedDetails = details.replace("\r\n", "\n").replace('\r', '\n').trim()
        val normalizedSummary = summary.trim().replace(LINE_BREAK_RUN_REGEX, " ").trim()
        val content = normalizedDetails.ifEmpty { normalizedSummary }
        val tag = normalizeVersionTag(versionName)
        return buildString {
            if (content.isNotEmpty()) {
                append(content)
                append("\n\n")
            }
            if (normalizedAttachment.isNotEmpty()) {
                append("Attachment: ")
                append(normalizedAttachment)
                append("\n\n")
            }
            append("---\n")
            append("App: Delta-Android ")
            append(tag)
            append(" (")
            append(versionCode)
            append(")")
        }
    }

    /** Percent-encodes a `mailto:` query parameter value per RFC 6068 (`%20` instead of `+`). */
    internal fun encodeMailtoParam(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /**
     * Builds an RFC 6068 `mailto:` URI for [email] with formatted `delta-android:` subject
     * and optional [body]. Throws [IllegalArgumentException] if [email] is not valid.
     */
    fun buildMailtoUri(
        email: String,
        summary: String,
        body: String = "",
        versionName: String = BuildConfig.VERSION_NAME,
    ): String {
        val normalizedEmail = normalizeEmail(email)
        require(isValidEmail(normalizedEmail)) { "Invalid feedback email address" }

        val subject = formatSubject(summary = summary, versionName = versionName)
        val encodedSubject = encodeMailtoParam(subject)
        val normalizedBody = body.replace("\r\n", "\n").replace('\r', '\n').trim()

        return if (normalizedBody.isEmpty()) {
            "mailto:$normalizedEmail?subject=$encodedSubject"
        } else {
            "mailto:$normalizedEmail?subject=$encodedSubject&body=${encodeMailtoParam(normalizedBody)}"
        }
    }

    /**
     * Produces a safe single-segment file name for a staged attachment: strips path separators and
     * control/reserved characters, limits length, and appends an extension derived from [extension]
     * when [rawName] has none.
     */
    fun sanitizeAttachmentName(
        rawName: String?,
        extension: String? = null,
    ): String {
        val baseName =
            rawName.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(UNSAFE_FILENAME_CHARS_REGEX, "_")
                .trim()
                .trim('.')
                .ifEmpty { DEFAULT_ATTACHMENT_BASENAME }
        val cleanExtension =
            extension?.trim()
                ?.trimStart('.')
                ?.lowercase()
                ?.takeIf { FILE_EXTENSION_REGEX.matches(it) }
        val withExtension =
            if (cleanExtension != null && !baseName.contains('.')) "$baseName.$cleanExtension" else baseName
        if (withExtension.length <= MAX_ATTACHMENT_NAME_LENGTH) return withExtension
        val dot = withExtension.lastIndexOf('.')
        val suffix = if (dot > 0 && withExtension.length - dot <= 9) withExtension.substring(dot) else ""
        return withExtension.take(MAX_ATTACHMENT_NAME_LENGTH - suffix.length) + suffix
    }

    /** Returns the user-visible display name of [uri] (via [OpenableColumns.DISPLAY_NAME]) when available. */
    fun queryDisplayName(
        context: Context,
        uri: Uri,
    ): String? {
        val fromResolver =
            try {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to query display name for feedback image", e)
                null
            }
        return fromResolver?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    /**
     * Copies the image at [sourceUri] into `cacheDir/feedback/` (replacing previously staged files) and
     * returns its [FileProvider] `content://` URI, or `null` when the source cannot be read.
     * [preferredName] (e.g. the name shown in the dialog) takes precedence over the resolver display name.
     */
    fun stageFeedbackImage(
        context: Context,
        sourceUri: Uri,
        preferredName: String? = null,
    ): StagedFeedbackImage? {
        val resolver = context.contentResolver
        val mimeType =
            resolver.getType(sourceUri)?.takeIf { it.startsWith("image/") } ?: DEFAULT_IMAGE_MIME_TYPE
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val displayName =
            sanitizeAttachmentName(
                rawName = preferredName?.takeIf { it.isNotBlank() } ?: queryDisplayName(context, sourceUri),
                extension = extension,
            )
        val feedbackDir = File(context.cacheDir, FEEDBACK_CACHE_DIR)
        return try {
            if (feedbackDir.exists()) {
                feedbackDir.listFiles()?.forEach { it.deleteRecursively() }
            } else if (!feedbackDir.mkdirs()) {
                throw IOException("Unable to create ${feedbackDir.absolutePath}")
            }
            val target = File(feedbackDir, displayName)
            val input = resolver.openInputStream(sourceUri) ?: throw IOException("Unable to open $sourceUri")
            input.use { source -> target.outputStream().use { sink -> source.copyTo(sink) } }
            if (target.length() == 0L) throw IOException("Empty feedback image")
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            StagedFeedbackImage(
                file = target,
                contentUri = contentUri,
                displayName = displayName,
                mimeType = mimeType,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stage feedback image", e)
            null
        }
    }

    fun getSavedEmail(prefs: SharedPreferences): String =
        normalizeEmail(prefs.getString(PREF_KEY_AGENT_FEEDBACK_EMAIL, DEFAULT_EMAIL))

    fun getSavedEmail(context: Context): String = getSavedEmail(UpdateManager.prefs(context))

    /**
     * Saves a trimmed valid email (or empty string to clear) into local [SharedPreferences].
     * Returns `false` without mutating preferences when [email] is non-empty and invalid.
     */
    fun saveEmail(
        prefs: SharedPreferences,
        email: String,
    ): Boolean {
        val normalized = normalizeEmail(email)
        if (normalized.isNotEmpty() && !isValidEmail(normalized)) {
            return false
        }
        prefs.edit().putString(PREF_KEY_AGENT_FEEDBACK_EMAIL, normalized).apply()
        return true
    }

    fun saveEmail(
        context: Context,
        email: String,
    ): Boolean = saveEmail(UpdateManager.prefs(context), email)

    /**
     * Dialog state after pressing "save email". Saving never dismisses the feedback dialog, so the already
     * entered title, description and image are kept.
     */
    data class EmailSaveState(
        val saved: Boolean,
        val emailInput: String,
        val lastSavedEmail: String,
        val emailError: Boolean,
    ) {
        val shouldDismiss: Boolean
            get() = false
    }

    /** Whether [emailInput] differs from [lastSavedEmail], i.e. whether the "save email" action is offered. */
    fun isEmailChanged(
        emailInput: String,
        lastSavedEmail: String,
    ): Boolean = normalizeEmail(emailInput) != normalizeEmail(lastSavedEmail)

    /**
     * Handles the "save email" action of the feedback dialog. A valid (or empty) email is persisted through
     * [persist] and becomes the new saved baseline; an invalid non-empty email is rejected with an error and
     * nothing is saved.
     */
    fun saveEmailFromDialog(
        emailInput: String,
        lastSavedEmail: String,
        persist: (String) -> Boolean,
    ): EmailSaveState {
        val normalized = normalizeEmail(emailInput)
        val valid = normalized.isEmpty() || isValidEmail(normalized)
        if (!valid || !persist(normalized)) {
            return EmailSaveState(
                saved = false,
                emailInput = emailInput,
                lastSavedEmail = lastSavedEmail,
                emailError = true,
            )
        }
        return EmailSaveState(
            saved = true,
            emailInput = normalized,
            lastSavedEmail = normalized,
            emailError = false,
        )
    }

    fun createSendToIntent(
        email: String,
        summary: String,
        body: String = "",
        versionName: String = BuildConfig.VERSION_NAME,
    ): Intent {
        val mailtoUri = buildMailtoUri(email = email, summary = summary, body = body, versionName = versionName)
        return Intent(Intent.ACTION_SENDTO, Uri.parse(mailtoUri))
    }

    /**
     * Opens the user's email app with a prefilled feedback email. Without [attachmentUri] this uses an
     * `ACTION_SENDTO` `mailto:` intent; with an image it stages the file into `cacheDir/feedback/`,
     * resolves the user's email app via a `mailto:` probe and starts its `ACTION_SEND` compose activity
     * explicitly (no selector, `EXTRA_STREAM` + `ClipData` + read grant), falling back to a chooser.
     */
    fun launchFeedbackEmail(
        context: Context,
        email: String,
        summary: String,
        details: String,
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        attachmentUri: Uri? = null,
        attachmentName: String? = null,
    ): Boolean {
        val staged = attachmentUri?.let { stageFeedbackImage(context, it, attachmentName) }
        if (attachmentUri != null && staged == null) {
            showToast(context, R.string.agent_feedback_image_attach_failed)
            return false
        }
        val effectiveAttachmentName = staged?.displayName
        val effectiveSummary = resolveSummary(summary, details, effectiveAttachmentName)
        val bodyBase =
            formatEmailBody(
                summary = effectiveSummary,
                details = details,
                versionName = versionName,
                versionCode = versionCode,
                attachmentName = effectiveAttachmentName,
            )
        val fullBody = "$bodyBase • Android ${Build.VERSION.SDK_INT}"
        val hostActivity = context.findHostActivity()
        val launcher = hostActivity ?: context

        if (staged == null) {
            val intent =
                createSendToIntent(
                    email = email,
                    summary = effectiveSummary,
                    body = fullBody,
                    versionName = versionName,
                )
            if (hostActivity == null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                launcher.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                showToast(context, R.string.agent_feedback_no_email_app)
                false
            }
        }

        val normalizedEmail = normalizeEmail(email)
        require(isValidEmail(normalizedEmail)) { "Invalid feedback email address" }
        val subject = formatSubject(summary = effectiveSummary, versionName = versionName)
        val needNewTask = hostActivity == null

        // Resolve the user's email app via a `mailto:` probe and launch its ACTION_SEND compose
        // activity explicitly (no selector). ACTION_SEND(image/*) + mailto: selector is rejected on
        // Android 15-17 (MATCH_NO_TYPE) or hijacked into the generic ColorOS share sheet.
        val targets = resolveEmailComposeTargets(context, staged.mimeType)
        for (target in targets) {
            val spec =
                FeedbackEmailTargetResolver.buildAttachmentSendSpec(
                    recipient = normalizedEmail,
                    subject = subject,
                    text = fullBody,
                    target = target,
                    attachmentUri = staged.contentUri.toString(),
                )
            runCatching {
                context.grantUriPermission(
                    target.packageName,
                    staged.contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val intent =
                spec.toIntent(staged.contentUri).apply {
                    if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            if (launcher.tryStartActivity(intent)) return true
        }

        // Last resort only when no email client exposes a visible ACTION_SEND compose activity.
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = staged.mimeType
                putExtra(Intent.EXTRA_EMAIL, arrayOf(normalizedEmail))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, fullBody)
                putExtra(Intent.EXTRA_STREAM, staged.contentUri)
                clipData = ClipData.newRawUri(FEEDBACK_CLIP_LABEL, staged.contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser =
            Intent.createChooser(sendIntent, null).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (needNewTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        if (launcher.tryStartActivity(chooser)) return true
        showToast(context, R.string.agent_feedback_no_email_app)
        return false
    }

    /**
     * Probes `mailto:` handlers (default handler first, then Gmail, then the other handlers, with
     * system resolvers/choosers filtered out) and returns each email app's `ACTION_SEND` compose
     * activity for [attachmentMime] (image, wildcard and `message/rfc822` fallbacks).
     */
    private fun resolveEmailComposeTargets(
        context: Context,
        attachmentMime: String,
    ): List<EmailComposeTarget> {
        val pm = context.packageManager
        val mailtoProbe = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        val defaultMailto = runCatching { pm.resolveDefaultActivity(mailtoProbe) }.getOrNull()
        val mailtoActivities =
            (
                pm.queryActivitiesSafe(mailtoProbe, PackageManager.MATCH_DEFAULT_ONLY) +
                    pm.queryActivitiesSafe(mailtoProbe, 0)
            ).distinct()
        return FeedbackEmailTargetResolver.resolveComposeTargets(
            defaultMailto = defaultMailto,
            mailtoActivities = mailtoActivities,
            attachmentMime = attachmentMime,
        ) { pkg, mime ->
            val probe =
                Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    setPackage(pkg)
                }
            pm.queryActivitiesSafe(probe, PackageManager.MATCH_DEFAULT_ONLY)
                .ifEmpty { pm.queryActivitiesSafe(probe, 0) }
        }
    }

    private fun FeedbackSendIntentSpec.toIntent(streamUri: Uri): Intent {
        val intent = Intent(action)
        val explicitClass = className
        if (explicitClass != null) {
            intent.component = ComponentName(packageName, explicitClass)
        } else {
            intent.setPackage(packageName)
        }
        intent.type = mimeType
        intent.putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_TEXT, text)
        intent.putExtra(Intent.EXTRA_STREAM, streamUri)
        intent.clipData = ClipData.newRawUri(clipLabel, streamUri)
        if (grantReadUriPermission) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // hasSelector is always false by contract: never set `selector` on an explicit ACTION_SEND.
        return intent
    }

    private fun Context.tryStartActivity(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun PackageManager.resolveDefaultActivity(intent: Intent): ResolvedEmailActivity? {
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            } else {
                @Suppress("DEPRECATION")
                resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        return info?.toResolvedEmailActivity()
    }

    private fun PackageManager.queryActivitiesSafe(
        intent: Intent,
        flags: Int,
    ): List<ResolvedEmailActivity> =
        runCatching {
            val infos =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    queryIntentActivities(intent, flags)
                }
            infos.mapNotNull { it.toResolvedEmailActivity() }
        }.getOrDefault(emptyList())

    private fun ResolveInfo.toResolvedEmailActivity(): ResolvedEmailActivity? {
        val info = activityInfo ?: return null
        val pkg = info.packageName ?: return null
        val name = info.name ?: return null
        return ResolvedEmailActivity(packageName = pkg, activityName = name)
    }

    private fun showToast(
        context: Context,
        messageRes: Int,
    ) {
        Toast.makeText(
            context.applicationContext,
            context.getString(messageRes),
            Toast.LENGTH_LONG,
        ).show()
    }

    private tailrec fun Context.findHostActivity(): Activity? =
        when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findHostActivity()
            else -> null
        }
}

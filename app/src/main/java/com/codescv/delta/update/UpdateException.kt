package com.codescv.delta.update

import java.io.IOException

/** Failure categories of [AppUpdater] and [UpdateManager], mapped to localized string resources in UI. */
enum class UpdateErrorCode {
    NETWORK,
    TIMEOUT,
    HTTP,
    RATE_LIMITED,
    NOT_FOUND,
    INVALID_RESPONSE,

    /** A redirect pointed to a non-HTTPS URL (e.g. an `https -> http` downgrade or `file://`). */
    INSECURE_REDIRECT,

    /** More than [AppUpdater.MAX_REDIRECTS] redirect hops. */
    TOO_MANY_REDIRECTS,

    /** Truncated/oversized/non-APK download, size outside sanity bounds, or `SHA256SUMS.txt` mismatch. */
    CORRUPT,
    WRONG_PACKAGE,
    NOT_NEWER,
    SIGNATURE_MISMATCH,
    NO_APK,
    INSTALLER,
    CANCELLED,
}

/** Diagnostic exception thrown by [AppUpdater]; [detail] is for logs/tests, UI maps [code] to resources. */
class UpdateException(
    val code: UpdateErrorCode,
    val detail: String = "",
    cause: Throwable? = null,
    val httpStatus: Int = 0,
) : IOException(detail.ifEmpty { code.name }, cause)

package com.codescv.delta.update

import com.codescv.delta.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Blocking HTTP client that checks GitHub Releases for updates and downloads APK assets.
 * Call all methods on `Dispatchers.IO`. [openConnection] and the URL parameters act as test seams.
 *
 * Security invariants (ported from the Lemuroid / miniplayer updaters):
 * - HTTPS only. Redirects are followed manually (at most [MAX_REDIRECTS] hops); any redirect to a
 *   non-HTTPS URL (`https -> http` downgrade, `file://`, ...) aborts with
 *   [UpdateErrorCode.INSECURE_REDIRECT]. Plain `http://127.0.0.1` is accepted only when
 *   [allowInsecureLoopback] is set (unit tests).
 * - When the release carries `SHA256SUMS.txt`, the APK's SHA-256 must match its line, otherwise
 *   [UpdateErrorCode.CORRUPT]. The ZIP structure (`PK\x03\x04` + `AndroidManifest.xml`) and the
 *   [minApkBytes]..[maxApkBytes] size window are always checked.
 * - GitHub rate limiting (403 + `X-RateLimit-Remaining: 0`, or 429) maps to [UpdateErrorCode.RATE_LIMITED].
 * - Every request carries `User-Agent: Delta-Android/<version>`.
 */
class AppUpdater(
    private val latestReleaseUrl: String = DEFAULT_LATEST_RELEASE_URL,
    private val releasesListUrl: String = DEFAULT_RELEASES_LIST_URL,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val minApkBytes: Long = MIN_APK_BYTES,
    private val maxApkBytes: Long = MAX_APK_BYTES,
    private val allowInsecureLoopback: Boolean = false,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    companion object {
        const val DEFAULT_LATEST_RELEASE_URL = "${BuildConfig.UPDATE_API_BASE}/releases/latest"
        const val DEFAULT_RELEASES_LIST_URL = "${BuildConfig.UPDATE_API_BASE}/releases?per_page=5"

        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_CHECKSUM_BYTES = 256 * 1024
        const val MIN_APK_BYTES = 20L * 1024 * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val USER_AGENT_PRODUCT = "Delta-Android"

        private const val PROGRESS_INTERVAL_MS = 100L
        private const val PROGRESS_INTERVAL_BYTES = 256L * 1024

        /** `Delta-Android/<version>` with the debug suffix stripped and unsafe characters removed. */
        fun userAgent(version: String?): String {
            val sanitized =
                SemanticVersion.stripDebugSuffix(version)
                    ?.replace(Regex("[^0-9A-Za-z._-]"), "")
                    ?.takeIf { it.isNotEmpty() }
            return if (sanitized != null) "$USER_AGENT_PRODUCT/$sanitized" else USER_AGENT_PRODUCT
        }

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private val userAgent: String = userAgent(appVersion)

    /**
     * Queries `/releases/latest` (falling back to `/releases?per_page=5` on HTTP 404 when all
     * releases are marked pre-release), parses the release metadata, and compares against
     * [currentVersion] with `-DEBUG` stripped.
     */
    fun checkLatestRelease(
        currentVersion: String,
        cancel: CancelToken? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): UpdateCheckResult {
        val baseVersion = SemanticVersion.stripDebugSuffix(currentVersion) ?: currentVersion
        val headers =
            mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to userAgent,
            )
        val jsonText =
            try {
                fetchText(latestReleaseUrl, headers, MAX_JSON_BYTES, cancel, timeoutMs)
            } catch (e: UpdateException) {
                if (e.code == UpdateErrorCode.NOT_FOUND && releasesListUrl.isNotEmpty()) {
                    fetchText(releasesListUrl, headers, MAX_JSON_BYTES, cancel, timeoutMs)
                } else {
                    throw e
                }
            }
        val latest = ReleaseJson.parse(jsonText)
        return UpdateCheckResult(
            currentVersion = baseVersion,
            latest = latest,
            updateAvailable = SemanticVersion.isNewerVersion(baseVersion, latest.tagName),
        )
    }

    /**
     * Returns the expected lowercase SHA-256 of [release]'s APK from its `SHA256SUMS.txt`, or null when
     * the release has no checksum file. A checksum file without an entry for the APK (or a malformed
     * digest) is treated as [UpdateErrorCode.CORRUPT].
     */
    fun fetchExpectedSha256(
        release: ReleaseInfo,
        cancel: CancelToken? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): String? {
        val apk = release.apkAsset ?: return null
        val sums = release.checksumsAsset ?: return null
        val text =
            fetchText(
                sums.downloadUrl,
                mapOf("Accept" to "text/plain, application/octet-stream", "User-Agent" to userAgent),
                MAX_CHECKSUM_BYTES,
                cancel,
                timeoutMs,
            )
        val expected =
            Sha256Sums.lookup(text, apk.name)
                ?: throw UpdateException(UpdateErrorCode.CORRUPT, "SHA256SUMS.txt has no entry for ${apk.name}")
        if (!Sha256Sums.isValidHex64(expected)) {
            throw UpdateException(UpdateErrorCode.CORRUPT, "Malformed SHA-256 digest for ${apk.name}")
        }
        return expected
    }

    /**
     * Full download pipeline for [release]: fetches `SHA256SUMS.txt` (when attached) and downloads the
     * APK asset into [destFile] with checksum + structure + [validator] verification.
     */
    fun downloadRelease(
        release: ReleaseInfo,
        destFile: File,
        cancelToken: CancelToken? = null,
        validator: ((File) -> Unit)? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        val asset = release.apkAsset ?: throw UpdateException(UpdateErrorCode.NO_APK, "Release has no APK asset")
        val expectedSha = fetchExpectedSha256(release, cancelToken)
        return downloadApk(
            url = asset.downloadUrl,
            destFile = destFile,
            cancelToken = cancelToken,
            expectedSize = asset.sizeBytes,
            expectedSha256 = expectedSha,
            validator = validator,
            onProgress = onProgress,
        )
    }

    /**
     * Downloads [url] (following up to [MAX_REDIRECTS] HTTPS redirects) into `destFile.tmp`, verifies
     * length, size window, optional [expectedSha256], ZIP/APK structure (`PK\x03\x04` +
     * `AndroidManifest.xml`) and optional [validator] (package name, versionCode, signing certificate),
     * then atomically renames to [destFile]. Any failure or cancellation deletes `.tmp` and never leaves
     * a partial file behind.
     */
    fun downloadApk(
        url: String,
        destFile: File,
        cancelToken: CancelToken? = null,
        connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
        expectedSize: Long = -1L,
        expectedSha256: String? = null,
        validator: ((File) -> Unit)? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.path + ".tmp")
        tmp.delete()
        val headers =
            mapOf(
                "Accept" to "application/octet-stream",
                "Accept-Encoding" to "identity",
                "User-Agent" to userAgent,
            )
        var conn: HttpURLConnection? = null
        var ok = false
        try {
            if (expectedSha256 != null && !Sha256Sums.isValidHex64(expectedSha256)) {
                throw UpdateException(UpdateErrorCode.CORRUPT, "Malformed expected SHA-256 digest")
            }
            checkSizeWindow(expectedSize, "Release asset size")
            conn = openFollowingRedirects(url, headers, cancelToken, connectTimeoutMs, readTimeoutMs)
            val status = conn.responseCode
            if (status != 200) throw httpError(conn, status)
            val contentType = conn.contentType.orEmpty()
            if (contentType.trim().startsWith("text/html", ignoreCase = true)) {
                throw UpdateException(UpdateErrorCode.CORRUPT, "Server returned text/html instead of APK")
            }
            val contentLength = conn.contentLengthLong
            checkSizeWindow(contentLength, "Content-Length")
            if (expectedSize > 0 && contentLength > 0 && contentLength != expectedSize) {
                throw UpdateException(
                    UpdateErrorCode.CORRUPT,
                    "Content-Length ($contentLength) does not match asset size ($expectedSize)",
                )
            }
            val total = contentLength.takeIf { it > 0 } ?: expectedSize
            val (written, actualSha) = streamToFile(conn.inputStream, tmp, total, cancelToken, onProgress)
            verifyZipApk(tmp, written, total, expectedSize)
            if (expectedSha256 != null) {
                val expected = expectedSha256.trim().lowercase(Locale.ROOT).toByteArray(Charsets.US_ASCII)
                val actual = actualSha.toByteArray(Charsets.US_ASCII)
                if (!MessageDigest.isEqual(expected, actual)) {
                    throw UpdateException(
                        UpdateErrorCode.CORRUPT,
                        "SHA-256 mismatch (expected ${expectedSha256.take(12)}…, got ${actualSha.take(12)}…)",
                    )
                }
            }
            validator?.invoke(tmp)
            destFile.delete()
            if (!tmp.renameTo(destFile)) {
                throw UpdateException(UpdateErrorCode.NETWORK, "Failed to rename downloaded APK to ${destFile.name}")
            }
            ok = true
            return destFile
        } catch (e: Exception) {
            throw mapError(e, cancelToken, readTimeoutMs)
        } finally {
            cancelToken?.detach()
            conn?.disconnect()
            if (!ok) tmp.delete()
        }
    }

    private fun checkSizeWindow(
        size: Long,
        what: String,
    ) {
        if (size <= 0) return
        if (size < minApkBytes || size > maxApkBytes) {
            throw UpdateException(
                UpdateErrorCode.CORRUPT,
                "$what $size outside sanity window [$minApkBytes, $maxApkBytes]",
            )
        }
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
        cancel: CancelToken?,
        timeoutMs: Int,
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = openFollowingRedirects(url, headers, cancel, timeoutMs, timeoutMs)
            val status = conn.responseCode
            if (status !in 200..299) throw httpError(conn, status)
            val text = readLimited(conn.inputStream, maxBytes, cancel)
            if (cancel?.isCancelled == true) throw cancelled()
            return text
        } catch (e: Exception) {
            throw mapError(e, cancel, timeoutMs)
        } finally {
            cancel?.detach()
            conn?.disconnect()
        }
    }

    private fun streamToFile(
        input: InputStream,
        tmp: File,
        total: Long,
        cancel: CancelToken?,
        onProgress: (Long, Long) -> Unit,
    ): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        var lastReportBytes = 0L
        var lastReportAt = 0L
        var reported = false
        input.use { ins ->
            val fos =
                try {
                    tmp.outputStream()
                } catch (e: IOException) {
                    throw UpdateException(UpdateErrorCode.NETWORK, "Cannot open temp file for writing", e)
                }
            fos.use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (cancel?.isCancelled == true) throw cancelled()
                    val n =
                        try {
                            ins.read(buf)
                        } catch (e: SocketTimeoutException) {
                            throw e
                        } catch (e: IOException) {
                            if (cancel?.isCancelled == true) throw cancelled()
                            throw UpdateException(
                                UpdateErrorCode.CORRUPT,
                                "Download stream interrupted at $written bytes",
                                e,
                            )
                        }
                    if (n < 0) break
                    if (n == 0) continue
                    try {
                        out.write(buf, 0, n)
                    } catch (e: IOException) {
                        throw UpdateException(UpdateErrorCode.NETWORK, "Disk write failed", e)
                    }
                    digest.update(buf, 0, n)
                    written += n
                    if (total > 0 && written > total) {
                        throw UpdateException(
                            UpdateErrorCode.CORRUPT,
                            "Download exceeded expected length ($written > $total)",
                        )
                    }
                    if (written > maxApkBytes) {
                        throw UpdateException(
                            UpdateErrorCode.CORRUPT,
                            "Download exceeded safety cap ($maxApkBytes bytes)",
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (
                        !reported ||
                        now - lastReportAt >= PROGRESS_INTERVAL_MS ||
                        written - lastReportBytes >= PROGRESS_INTERVAL_BYTES
                    ) {
                        onProgress(written, total)
                        reported = true
                        lastReportAt = now
                        lastReportBytes = written
                    }
                }
                try {
                    out.flush()
                    out.fd.sync()
                } catch (e: IOException) {
                    throw UpdateException(UpdateErrorCode.NETWORK, "Failed to sync downloaded file", e)
                }
            }
        }
        if (cancel?.isCancelled == true) throw cancelled()
        if (lastReportBytes != written || !reported) {
            onProgress(written, total)
        }
        return written to digest.digest().toHex()
    }

    private fun verifyZipApk(
        tmp: File,
        written: Long,
        total: Long,
        expectedSize: Long,
    ) {
        if (total > 0 && written != total) {
            throw UpdateException(UpdateErrorCode.CORRUPT, "Incomplete download ($written / $total bytes)")
        }
        if (expectedSize > 0 && written != expectedSize) {
            throw UpdateException(UpdateErrorCode.CORRUPT, "Size mismatch ($written != expected $expectedSize bytes)")
        }
        if (written < minApkBytes) {
            throw UpdateException(UpdateErrorCode.CORRUPT, "APK too small ($written < $minApkBytes bytes)")
        }
        val head = ByteArray(4)
        val n = if (written >= 4) tmp.inputStream().use { readFully(it, head) } else 0
        if (n < 4 || !head.contentEquals(APK_MAGIC)) {
            throw UpdateException(UpdateErrorCode.CORRUPT, "Missing ZIP/APK magic header")
        }
        val hasManifest =
            try {
                ZipFile(tmp).use { it.getEntry("AndroidManifest.xml") != null }
            } catch (e: Exception) {
                throw UpdateException(UpdateErrorCode.CORRUPT, "Corrupt or truncated ZIP archive", e)
            }
        if (!hasManifest) {
            throw UpdateException(UpdateErrorCode.CORRUPT, "ZIP archive has no AndroidManifest.xml")
        }
    }

    /**
     * Follows up to [MAX_REDIRECTS] HTTP redirects manually across hosts (e.g. `github.com` ->
     * `objects.githubusercontent.com`), refusing anything that is not HTTPS.
     */
    private fun openFollowingRedirects(
        url: String,
        headers: Map<String, String>,
        cancel: CancelToken?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        @Suppress("DEPRECATION")
        var current =
            try {
                URL(url)
            } catch (e: MalformedURLException) {
                throw UpdateException(UpdateErrorCode.NETWORK, "Invalid URL: $url", e)
            }
        if (!isSecure(current)) {
            throw UpdateException(UpdateErrorCode.NETWORK, "Refusing non-HTTPS URL: $current")
        }
        var hops = 0
        while (true) {
            if (cancel?.isCancelled == true) throw cancelled()
            val conn = openConnection(current)
            var handedOff = false
            try {
                cancel?.attach { conn.disconnect() }
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs.coerceAtLeast(1_000)
                conn.readTimeout = readTimeoutMs.coerceAtLeast(1_000)
                headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
                val status = conn.responseCode
                if (status !in REDIRECT_CODES) {
                    handedOff = true
                    return conn
                }
                val location = conn.getHeaderField("Location")?.trim()
                if (location.isNullOrEmpty()) {
                    throw UpdateException(
                        UpdateErrorCode.HTTP,
                        "Redirect missing Location header (HTTP $status)",
                        httpStatus = status,
                    )
                }
                if (++hops > MAX_REDIRECTS) {
                    throw UpdateException(UpdateErrorCode.TOO_MANY_REDIRECTS, "Too many redirects (>$MAX_REDIRECTS)")
                }
                @Suppress("DEPRECATION")
                val next =
                    try {
                        URL(current, location)
                    } catch (e: MalformedURLException) {
                        throw UpdateException(
                            UpdateErrorCode.INSECURE_REDIRECT,
                            "Malformed redirect Location: $location",
                            e,
                            status,
                        )
                    }
                val downgrade =
                    current.protocol.equals("https", ignoreCase = true) &&
                        !next.protocol.equals("https", ignoreCase = true)
                if (downgrade || !isSecure(next)) {
                    throw UpdateException(
                        UpdateErrorCode.INSECURE_REDIRECT,
                        "Insecure redirect (${current.protocol} -> ${next.protocol}): $next",
                        httpStatus = status,
                    )
                }
                current = next
            } finally {
                if (!handedOff) {
                    cancel?.detach()
                    conn.disconnect()
                }
            }
        }
    }

    private fun isSecure(url: URL): Boolean {
        val protocol = url.protocol.lowercase(Locale.ROOT)
        val host = url.host.orEmpty()
        if (host.isEmpty()) return false
        if (protocol == "https") return true
        return allowInsecureLoopback && protocol == "http" && (host == "127.0.0.1" || host == "localhost")
    }

    private fun httpError(
        conn: HttpURLConnection,
        status: Int,
    ): UpdateException {
        val body = runCatching { conn.errorStream?.use { readPrefix(it, 4096) } }.getOrNull().orEmpty()
        val rateLimited =
            status == 429 ||
                (
                    status == 403 &&
                        (
                            conn.getHeaderField("X-RateLimit-Remaining")?.trim() == "0" ||
                                body.contains("rate limit", ignoreCase = true)
                        )
                )
        return when {
            rateLimited -> UpdateException(UpdateErrorCode.RATE_LIMITED, "GitHub API rate limited", httpStatus = status)
            status == 404 -> UpdateException(UpdateErrorCode.NOT_FOUND, "Release not found (404)", httpStatus = 404)
            else -> UpdateException(UpdateErrorCode.HTTP, "HTTP $status: ${body.trim().take(160)}", httpStatus = status)
        }
    }

    private fun mapError(
        e: Exception,
        cancel: CancelToken?,
        timeoutMs: Int,
    ): UpdateException {
        if (cancel?.isCancelled == true) {
            return if (e is UpdateException && e.code == UpdateErrorCode.CANCELLED) {
                e
            } else {
                UpdateException(UpdateErrorCode.CANCELLED, "Cancelled", e)
            }
        }
        if (e is UpdateException) return e
        return when (e) {
            is SocketTimeoutException -> UpdateException(UpdateErrorCode.TIMEOUT, "Timed out after $timeoutMs ms", e)
            is UnknownServiceException -> UpdateException(UpdateErrorCode.NETWORK, "Cleartext HTTP not permitted", e)
            is UnknownHostException -> UpdateException(UpdateErrorCode.NETWORK, "Unknown host: ${e.message}", e)
            is IOException -> UpdateException(UpdateErrorCode.NETWORK, e.message ?: "Network I/O error", e)
            else -> UpdateException(UpdateErrorCode.NETWORK, "${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun cancelled() = UpdateException(UpdateErrorCode.CANCELLED, "Cancelled")

    private fun readLimited(
        input: InputStream,
        limit: Int,
        cancel: CancelToken?,
    ): String {
        input.use { ins ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                if (cancel?.isCancelled == true) throw cancelled()
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) {
                    throw UpdateException(UpdateErrorCode.INVALID_RESPONSE, "Response exceeds $limit bytes")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun readPrefix(
        input: InputStream,
        limit: Int,
    ): String {
        val buf = ByteArray(limit)
        val n = readFully(input, buf)
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private fun readFully(
        input: InputStream,
        buf: ByteArray,
    ): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}

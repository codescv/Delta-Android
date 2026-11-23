package com.codescv.delta.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val scope: String,
)

class CancellationIOException(
    message: String = "Google sign-in was cancelled",
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * In-process handoff between [OAuthRedirectActivity] and an active [GoogleOAuthFlow].
 *
 * Uses a single active [CompletableDeferred] fenced by a monotonic session ID so superseded,
 * cancelled, or stale callbacks never leak or cross-contaminate a newer sign-in attempt.
 */
class OAuthRedirectCoordinator {

    companion object {
        val Default = OAuthRedirectCoordinator()
    }

    private class Session(
        val id: Long,
        val deferred: CompletableDeferred<String>,
        var leftAppForBrowser: Boolean = false,
    )

    inner class SessionHandle internal constructor(
        val id: Long,
        private val deferred: CompletableDeferred<String>,
    ) {
        suspend fun awaitUri(): String = deferred.await()

        fun cancel(reason: String = "Google sign-in was cancelled") {
            cancelSessionIfActive(id, reason)
        }

        fun finish() {
            finishSession(id)
        }
    }

    private val lock = Any()
    private val nextSessionId = AtomicLong(1L)
    private var activeSession: Session? = null

    fun beginSession(): SessionHandle {
        val id = nextSessionId.getAndIncrement()
        val deferred = CompletableDeferred<String>()
        synchronized(lock) {
            activeSession?.deferred?.completeExceptionally(
                CancellationIOException("Superseded by a new Google sign-in"),
            )
            activeSession = Session(id = id, deferred = deferred)
        }
        return SessionHandle(id = id, deferred = deferred)
    }

    /** Delivers a redirect URI from [OAuthRedirectActivity]. Returns true if an active flow accepted it. */
    fun deliver(rawUri: String): Boolean {
        val target = synchronized(lock) {
            val current = activeSession ?: return false
            activeSession = null
            current
        }
        return target.deferred.complete(rawUri)
    }

    fun cancelActive(reason: String = "Google sign-in was cancelled"): Boolean {
        val target = synchronized(lock) {
            val current = activeSession ?: return false
            activeSession = null
            current
        }
        return target.deferred.completeExceptionally(CancellationIOException(reason))
    }

    fun cancelSessionIfActive(sessionId: Long, reason: String = "Google sign-in was cancelled"): Boolean {
        val target = synchronized(lock) {
            val current = activeSession ?: return false
            if (current.id != sessionId) return false
            activeSession = null
            current
        }
        return target.deferred.completeExceptionally(CancellationIOException(reason))
    }

    fun finishSession(sessionId: Long) {
        synchronized(lock) {
            if (activeSession?.id == sessionId) {
                activeSession = null
            }
        }
    }

    fun hasActiveSession(): Boolean = synchronized(lock) { activeSession != null }

    fun markLeftForBrowser() {
        synchronized(lock) {
            activeSession?.leftAppForBrowser = true
        }
    }

    /**
     * If an active session previously left the app for the browser, clears that flag and returns
     * its session ID so the caller can cancel it after a brief grace period if no redirect arrives.
     */
    fun consumeReturnedFromBrowserSessionId(): Long? = synchronized(lock) {
        val current = activeSession ?: return null
        if (!current.leftAppForBrowser) return null
        current.leftAppForBrowser = false
        current.id
    }
}

/**
 * Pure-JVM OAuth 2.0 PKCE (`S256`) + Custom URI Scheme sign-in flow for Google Android OAuth clients.
 *
 * - Public client: no `client_secret` is sent during code exchange (verified by Google via PKCE +
 *   Android package name + signing certificate SHA-1).
 * - Derives redirect URI `com.googleusercontent.apps.<prefix>:/oauth2redirect` from the client ID.
 * - Uses single scope `https://www.googleapis.com/auth/drive.file`, `access_type=offline`, and
 *   `prompt=consent` so Google returns a `refresh_token`.
 * - Strictly verifies `scheme`, path, and constant-time `state` match on the redirect callback.
 */
class GoogleOAuthFlow(
    private val clientId: String,
    private val openBrowser: (String) -> Unit,
    private val redirectCoordinator: OAuthRedirectCoordinator = OAuthRedirectCoordinator.Default,
    private val tokenEndpoint: String = DEFAULT_OAUTH_TOKEN_ENDPOINT,
    private val authEndpoint: String = DEFAULT_OAUTH_AUTH_ENDPOINT,
    private val random: SecureRandom = SecureRandom(),
    private val clock: SyncClock = SyncClock.SYSTEM,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : Closeable {

    companion object {
        const val GOOGLE_CLIENT_ID_SUFFIX = ".apps.googleusercontent.com"
        const val REDIRECT_SCHEME_PREFIX = "com.googleusercontent.apps."
        const val REDIRECT_PATH = "/oauth2redirect"

        private val VALID_CLIENT_PREFIX_REGEX = Regex("^[A-Za-z0-9.-]+$")

        /**
         * Derives the reversed-DNS custom URI scheme for a Google OAuth Client ID:
         * `<prefix>.apps.googleusercontent.com` -> `com.googleusercontent.apps.<prefix>`.
         */
        fun deriveRedirectScheme(clientId: String): String {
            val trimmed = clientId.trim()
            if (!trimmed.endsWith(GOOGLE_CLIENT_ID_SUFFIX, ignoreCase = true)) {
                throw IllegalArgumentException(
                    "OAuth Client ID must end with $GOOGLE_CLIENT_ID_SUFFIX",
                )
            }
            val prefix = trimmed.dropLast(GOOGLE_CLIENT_ID_SUFFIX.length)
            if (prefix.isEmpty() || !VALID_CLIENT_PREFIX_REGEX.matches(prefix)) {
                throw IllegalArgumentException("OAuth Client ID has an invalid prefix")
            }
            return "$REDIRECT_SCHEME_PREFIX${prefix.lowercase(Locale.US)}"
        }

        /**
         * Derives the Android custom URI scheme redirect URI:
         * `com.googleusercontent.apps.<prefix>:/oauth2redirect`.
         */
        fun deriveRedirectUri(clientId: String): String =
            "${deriveRedirectScheme(clientId)}:$REDIRECT_PATH"

        fun deriveRedirectUriOrNull(clientId: String): String? =
            runCatching { deriveRedirectUri(clientId) }.getOrNull()

        fun generateCodeVerifier(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun computeCodeChallengeS256(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun generateState(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        internal fun parseQueryParams(query: String): Map<String, String> {
            if (query.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
                val rawVal = if (eq >= 0) pair.substring(eq + 1) else ""
                val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
                val value = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
                if (key.isNotEmpty() && key !in result) {
                    result[key] = value
                }
            }
            return result
        }

        /**
         * Parses and strictly validates an OAuth redirect URI:
         * - Verifies custom URI scheme matches [expectedScheme] (case-insensitive)
         * - Rejects opaque URIs, unexpected authorities/hosts, and unexpected paths
         * - Strictly verifies `state` matches [expectedState] in constant time
         * - Maps OAuth `error` / `error_description` to [GDriveAuthException]
         * - Returns the non-empty authorization `code`
         */
        internal fun parseAndVerifyCallback(
            rawUri: String,
            expectedScheme: String,
            expectedState: String,
        ): String {
            val uri = try {
                URI(rawUri.trim())
            } catch (e: Exception) {
                throw GDriveAuthException("Invalid OAuth redirect URI", needsInteraction = true, cause = e)
            }

            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme != expectedScheme.lowercase(Locale.US)) {
                throw GDriveAuthException("Unexpected OAuth redirect scheme", needsInteraction = true)
            }

            if (uri.isOpaque || !uri.rawAuthority.isNullOrEmpty() || uri.rawPath != REDIRECT_PATH) {
                throw GDriveAuthException("Unexpected OAuth redirect path", needsInteraction = true)
            }

            val params = parseQueryParams(uri.rawQuery.orEmpty())
            val gotState = params["state"]
            val stateMatches = !gotState.isNullOrEmpty() && MessageDigest.isEqual(
                gotState.toByteArray(Charsets.UTF_8),
                expectedState.toByteArray(Charsets.UTF_8),
            )
            if (!stateMatches) {
                throw GDriveAuthException(
                    "Invalid OAuth state — please try signing in again",
                    needsInteraction = true,
                )
            }

            val error = params["error"]?.trim().orEmpty()
            if (error.isNotEmpty()) {
                val desc = params["error_description"]?.trim().orEmpty()
                val msg = when {
                    error == "access_denied" && desc.isEmpty() -> "Google sign-in was denied"
                    desc.isNotEmpty() -> "Google sign-in failed (${redactSecrets("$error: $desc")})"
                    else -> "Google sign-in failed (${redactSecrets(error)})"
                }
                throw GDriveAuthException(msg, needsInteraction = true)
            }

            return params["code"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw GDriveAuthException(
                    "OAuth redirect did not include an authorization code",
                    needsInteraction = true,
                )
        }

        internal fun parseTokenResponse(rawJson: String, nowMs: Long): OAuthTokens {
            val obj = runCatching { syncJson.parseToJsonElement(rawJson).jsonObject }.getOrNull()
                ?: throw IOException("OAuth token response is not valid JSON")
            val accessToken = obj.str("access_token")?.takeIf { it.isNotBlank() }
                ?: throw IOException("OAuth token response missing access_token")
            val refreshToken = obj.str("refresh_token") ?: ""
            val expiresIn = (obj.long("expires_in", 3600L)).coerceAtLeast(60L)
            val expiresAtMs = nowMs + expiresIn * 1000L
            val scope = obj.str("scope") ?: DRIVE_SCOPE
            return OAuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtMs = expiresAtMs,
                scope = scope,
            )
        }
    }

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeSession: OAuthRedirectCoordinator.SessionHandle? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    var authUrl: String? = null
        private set

    suspend fun authenticate(timeoutMs: Long = 5 * 60_000L): OAuthTokens {
        val trimmedClientId = clientId.trim()
        require(trimmedClientId.isNotEmpty()) { "OAuth Client ID is required" }
        val redirectScheme = deriveRedirectScheme(trimmedClientId)
        val redirectUri = "$redirectScheme:$REDIRECT_PATH"

        if (cancelled.get()) throw CancellationIOException()

        val verifier = generateCodeVerifier(random)
        val challenge = computeCodeChallengeS256(verifier)
        val expectedState = generateState(random)

        val session = redirectCoordinator.beginSession()
        activeSession = session
        if (cancelled.get()) {
            session.cancel()
            session.finish()
            activeSession = null
            throw CancellationIOException()
        }

        val authCode: String = try {
            val builtAuthUrl = buildAuthUrl(redirectUri, challenge, expectedState)
            authUrl = builtAuthUrl
            openBrowser(builtAuthUrl)

            val callbackUri = try {
                withTimeout(timeoutMs.coerceAtLeast(1L)) {
                    session.awaitUri()
                }
            } catch (e: TimeoutCancellationException) {
                throw SocketTimeoutException("Timed out waiting for Google sign-in in browser")
            }

            parseAndVerifyCallback(
                rawUri = callbackUri,
                expectedScheme = redirectScheme,
                expectedState = expectedState,
            )
        } finally {
            session.finish()
            if (activeSession === session) {
                activeSession = null
            }
        }

        if (cancelled.get()) throw CancellationIOException()
        return withContext(Dispatchers.IO) {
            exchangeAuthorizationCode(
                code = authCode,
                redirectUri = redirectUri,
                codeVerifier = verifier,
            )
        }
    }

    fun cancel(reason: String = "Google sign-in was cancelled") {
        cancelled.set(true)
        activeSession?.cancel(reason)
        runCatching { activeConnection?.disconnect() }
    }

    override fun close() {
        cancel()
    }

    internal fun buildAuthUrl(redirectUri: String, challenge: String, state: String): String {
        val base = authEndpoint.trimEnd('?')
        val sep = if ('?' in base) '&' else '?'
        return buildString {
            append(base).append(sep)
            append("client_id=").append(urlEncode(clientId.trim()))
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&response_type=code")
            append("&scope=").append(urlEncode(DRIVE_SCOPE))
            append("&code_challenge=").append(urlEncode(challenge))
            append("&code_challenge_method=S256")
            append("&state=").append(urlEncode(state))
            append("&access_type=offline")
            append("&prompt=consent")
        }
    }

    private fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): OAuthTokens {
        if (cancelled.get()) throw CancellationIOException()
        val form = buildString {
            append("code=").append(urlEncode(code))
            append("&client_id=").append(urlEncode(clientId.trim()))
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&grant_type=authorization_code")
            append("&code_verifier=").append(urlEncode(codeVerifier))
        }
        val bodyBytes = form.toByteArray(Charsets.UTF_8)
        val conn = (URL(tokenEndpoint).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setFixedLengthStreamingMode(bodyBytes.size)
        }
        activeConnection = conn
        try {
            if (cancelled.get()) throw CancellationIOException()
            conn.outputStream.use { it.write(bodyBytes) }
            val status = conn.responseCode
            val raw = readHttpBody(conn, status)
            if (cancelled.get()) throw CancellationIOException()
            if (status !in 200..299) {
                val parsed = runCatching { syncJson.parseToJsonElement(raw).jsonObject }.getOrNull()
                val errCode = parsed?.str("error") ?: ""
                val errDesc = parsed?.str("error_description") ?: ""
                val detail = listOf(errCode, errDesc).filter { it.isNotBlank() }.joinToString(": ")
                    .ifBlank { "HTTP $status" }
                throw GDriveAuthException(
                    message = "OAuth token exchange failed (${redactSecrets(detail)})",
                    needsInteraction = true,
                )
            }
            return parseTokenResponse(raw, clock.nowMs())
        } catch (e: IOException) {
            if (cancelled.get() && e !is CancellationIOException) {
                throw CancellationIOException(cause = e)
            }
            throw e
        } finally {
            if (activeConnection === conn) activeConnection = null
            conn.disconnect()
        }
    }
}

internal fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.long(key: String, default: Long = 0L): Long =
    this[key]?.jsonPrimitive?.longOrNull
        ?: this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: default

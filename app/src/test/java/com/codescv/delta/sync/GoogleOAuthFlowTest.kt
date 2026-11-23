package com.codescv.delta.sync

import com.codescv.delta.BuildConfig
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.settings.PreferencesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

class GoogleOAuthFlowTest {

    companion object {
        private const val TEST_ANDROID_CLIENT_ID =
            "304560912778-7ln54oj60t2v9mt0nqri70hl11joa0v9.apps.googleusercontent.com"
        private const val TEST_SCHEME =
            "com.googleusercontent.apps.304560912778-7ln54oj60t2v9mt0nqri70hl11joa0v9"
        private const val TEST_REDIRECT_URI = "$TEST_SCHEME:/oauth2redirect"
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // 1. Redirect URI derivation
    // =========================================================================

    @Test
    fun deriveRedirectUri_matchesExpectedAndroidCustomSchemeFormat() {
        assertEquals(TEST_SCHEME, GoogleOAuthFlow.deriveRedirectScheme(TEST_ANDROID_CLIENT_ID))
        assertEquals(TEST_REDIRECT_URI, GoogleOAuthFlow.deriveRedirectUri(TEST_ANDROID_CLIENT_ID))
        assertEquals(
            TEST_REDIRECT_URI,
            GoogleOAuthFlow.deriveRedirectUri("  $TEST_ANDROID_CLIENT_ID  "),
        )
        assertEquals(
            "com.googleusercontent.apps.12345-abc:/oauth2redirect",
            GoogleOAuthFlow.deriveRedirectUri("12345-ABC.apps.googleusercontent.com"),
        )
    }

    @Test
    fun deriveRedirectUri_rejectsMalformedClientIds() {
        val invalid = listOf(
            "",
            "   ",
            "not-a-google-client-id",
            ".apps.googleusercontent.com",
            "bad/prefix.apps.googleusercontent.com",
            "bad prefix.apps.googleusercontent.com",
            "bad:prefix.apps.googleusercontent.com",
        )
        for (bad in invalid) {
            assertNull("Expected null for '$bad'", GoogleOAuthFlow.deriveRedirectUriOrNull(bad))
            try {
                GoogleOAuthFlow.deriveRedirectUri(bad)
                fail("Expected IllegalArgumentException for '$bad'")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    // =========================================================================
    // 2. Callback URI parsing & strict verification
    // =========================================================================

    @Test
    fun parseAndVerifyCallback_acceptsValidEmptyAuthorityAndTripleSlashAndUrlEncodedCode() {
        val code1 = GoogleOAuthFlow.parseAndVerifyCallback(
            rawUri = "$TEST_REDIRECT_URI?state=st-1&code=4%2F0Abcd_123-xyz&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file",
            expectedScheme = TEST_SCHEME,
            expectedState = "st-1",
        )
        assertEquals("4/0Abcd_123-xyz", code1)

        val code2 = GoogleOAuthFlow.parseAndVerifyCallback(
            rawUri = "$TEST_SCHEME:///oauth2redirect?code=code-triple&state=st-2",
            expectedScheme = TEST_SCHEME,
            expectedState = "st-2",
        )
        assertEquals("code-triple", code2)
    }

    @Test
    fun parseAndVerifyCallback_rejectsStateMismatchMissingStateWrongSchemeHostAndPath() {
        val cases = listOf(
            "$TEST_REDIRECT_URI?code=abc&state=wrong-state",
            "$TEST_REDIRECT_URI?code=abc",
            "$TEST_REDIRECT_URI?code=abc&state=",
            "com.googleusercontent.apps.other-client:/oauth2redirect?code=abc&state=expected",
            "$TEST_SCHEME://evil.example/oauth2redirect?code=abc&state=expected",
            "$TEST_SCHEME:/wrongpath?code=abc&state=expected",
            "$TEST_SCHEME:oauth2redirect?code=abc&state=expected",
            "$TEST_REDIRECT_URI?state=expected",
        )
        for (uri in cases) {
            try {
                GoogleOAuthFlow.parseAndVerifyCallback(
                    rawUri = uri,
                    expectedScheme = TEST_SCHEME,
                    expectedState = "expected",
                )
                fail("Expected GDriveAuthException for $uri")
            } catch (e: GDriveAuthException) {
                assertTrue(e.needsInteraction)
            }
        }
    }

    @Test
    fun parseAndVerifyCallback_reportsErrorAndDescription() {
        try {
            GoogleOAuthFlow.parseAndVerifyCallback(
                rawUri = "$TEST_REDIRECT_URI?error=invalid_request&error_description=Custom+URI+scheme+disabled&state=s1",
                expectedScheme = TEST_SCHEME,
                expectedState = "s1",
            )
            fail("Expected GDriveAuthException")
        } catch (e: GDriveAuthException) {
            assertTrue(e.needsInteraction)
            assertTrue(e.message!!.contains("invalid_request"))
            assertTrue(e.message!!.contains("Custom URI scheme disabled"))
        }
    }

    // =========================================================================
    // 3. Flow state mismatch, cancellation, browser-return, timeout, no leaks
    // =========================================================================

    @Test
    fun authenticate_stateMismatch_failsWithoutCallingTokenEndpoint() = runBlocking {
        val tokenCalled = AtomicBoolean(false)
        val coordinator = OAuthRedirectCoordinator()
        MockDriveHttpServer().use { server ->
            server.onTokenRequest = {
                tokenCalled.set(true)
                200 to """{"access_token":"should-not-happen","expires_in":3600}"""
            }
            val flow = GoogleOAuthFlow(
                clientId = TEST_ANDROID_CLIENT_ID,
                redirectCoordinator = coordinator,
                tokenEndpoint = server.tokenEndpoint,
                openBrowser = { url ->
                    val redirectUri = parseUrlQuery(url).getValue("redirect_uri")
                    assertTrue(coordinator.deliver("$redirectUri?code=stolen&state=wrong-state"))
                },
            )
            try {
                flow.authenticate(timeoutMs = 5_000L)
                fail("Expected GDriveAuthException")
            } catch (e: GDriveAuthException) {
                assertTrue(e.needsInteraction)
                assertTrue(e.message!!.contains("state", ignoreCase = true))
            }
            assertFalse("Token endpoint must not be called on state mismatch", tokenCalled.get())
            assertFalse(coordinator.hasActiveSession())
        }
    }

    @Test
    fun authenticate_timeout_throwsSocketTimeoutExceptionAndCleansUpSession() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val flow = GoogleOAuthFlow(
            clientId = TEST_ANDROID_CLIENT_ID,
            redirectCoordinator = coordinator,
            openBrowser = { /* never delivers redirect */ },
        )
        try {
            flow.authenticate(timeoutMs = 80L)
            fail("Expected SocketTimeoutException")
        } catch (_: SocketTimeoutException) {
            // expected
        }
        assertFalse(coordinator.hasActiveSession())
        assertFalse(coordinator.deliver("$TEST_REDIRECT_URI?code=late&state=s"))
    }

    @Test
    fun authenticate_cancel_throwsCancellationIOExceptionAndCleansUpSession() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val browserOpened = CompletableDeferred<Unit>()
        val flow = GoogleOAuthFlow(
            clientId = TEST_ANDROID_CLIENT_ID,
            redirectCoordinator = coordinator,
            openBrowser = { browserOpened.complete(Unit) },
        )
        val job = async(Dispatchers.IO) {
            runCatching { flow.authenticate(timeoutMs = 10_000L) }
        }
        withTimeout(2_000L) { browserOpened.await() }
        assertTrue(coordinator.hasActiveSession())
        flow.cancel()
        val outcome = withTimeout(2_000L) { job.await() }
        assertTrue(
            "Expected CancellationIOException, got $outcome",
            outcome.exceptionOrNull() is CancellationIOException,
        )
        assertFalse(coordinator.hasActiveSession())
    }

    @Test
    fun authenticate_openBrowserFailure_cleansUpSession() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val flow = GoogleOAuthFlow(
            clientId = TEST_ANDROID_CLIENT_ID,
            redirectCoordinator = coordinator,
            openBrowser = { throw IOException("No browser installed") },
        )
        try {
            flow.authenticate(timeoutMs = 5_000L)
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("No browser"))
        }
        assertFalse(coordinator.hasActiveSession())
    }

    // =========================================================================
    // 4. GoogleDriveSyncManager integration: sign-in, return-cancel, migration
    // =========================================================================

    @Test
    fun syncManager_signIn_usesAndroidPkceWithoutClientSecret_andRefreshOmitsClientSecret() = runBlocking {
        val root = tempFolder.newFolder("mgrSignIn")
        val saves = SaveFileManager(root)
        val prefs = PreferencesStore(File(root, "preferences.json"))
        val secretStore = InMemorySyncSecretStore()
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tokenForms = mutableListOf<Map<String, String>>()

        try {
            MockDriveHttpServer(
                validAccessToken = "access-1",
                userEmail = "delta@example.com",
                userDisplayName = "Delta Player",
            ).use { server ->
                server.onTokenRequest = { form ->
                    synchronized(tokenForms) { tokenForms.add(form) }
                    when (form["grant_type"]) {
                        "authorization_code" ->
                            200 to """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600,"scope":"$DRIVE_SCOPE"}"""
                        "refresh_token" ->
                            200 to """{"access_token":"access-2","expires_in":3600}"""
                        else -> 400 to """{"error":"unsupported_grant_type"}"""
                    }
                }

                val manager = GoogleDriveSyncManager(
                    secretStore = secretStore,
                    preferences = prefs,
                    romsProvider = { emptyList() },
                    saves = saves,
                    filesDir = root,
                    scope = scope,
                    defaultClientId = TEST_ANDROID_CLIENT_ID,
                    redirectCoordinator = coordinator,
                    tokenEndpoint = server.tokenEndpoint,
                    apiBase = server.apiBase,
                    uploadBase = server.uploadBase,
                )

                val info = manager.signIn(
                    openBrowser = { url ->
                        val params = parseUrlQuery(url)
                        val redirectUri = params.getValue("redirect_uri")
                        val state = params.getValue("state")
                        assertEquals(TEST_REDIRECT_URI, redirectUri)
                        manager.onAppPaused()
                        assertTrue(coordinator.deliver("$redirectUri?code=code-mgr&state=$state"))
                        manager.onAppResumed(gracePeriodMs = 10L)
                    },
                )

                assertEquals("delta@example.com", info.email)
                assertEquals("Delta Player", info.displayName)
                assertTrue(manager.uiState.value.isSignedIn)
                assertFalse(manager.uiState.value.isSigningIn)
                assertEquals("", secretStore.load().clientSecret)

                // Verify token exchange omitted client_secret
                assertEquals(1, tokenForms.size)
                assertFalse("client_secret" in tokenForms[0])

                // Now verify refresh also omits client_secret
                val provider = OAuthTokenProvider(
                    clientId = secretStore.load().clientId,
                    clientSecret = secretStore.load().clientSecret,
                    initialAccessToken = "access-1",
                    initialRefreshToken = secretStore.load().refreshToken,
                    initialExpiresAtMs = 1L, // expired
                    tokenEndpoint = server.tokenEndpoint,
                )
                assertEquals("access-2", provider.accessToken())
                assertEquals(2, tokenForms.size)
                assertEquals("refresh_token", tokenForms[1]["grant_type"])
                assertFalse("client_secret" in tokenForms[1])
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syncManager_returningFromBrowserWithoutCallback_cancelsSignInCleanly() = runBlocking {
        val root = tempFolder.newFolder("mgrReturnCancel")
        val saves = SaveFileManager(root)
        val prefs = PreferencesStore(File(root, "preferences.json"))
        val secretStore = InMemorySyncSecretStore()
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val browserOpened = CompletableDeferred<Unit>()

        try {
            val manager = GoogleDriveSyncManager(
                secretStore = secretStore,
                preferences = prefs,
                romsProvider = { emptyList() },
                saves = saves,
                filesDir = root,
                scope = scope,
                defaultClientId = TEST_ANDROID_CLIENT_ID,
                redirectCoordinator = coordinator,
            )

            val signInJob = async(Dispatchers.IO) {
                runCatching {
                    manager.signIn(
                        openBrowser = { browserOpened.complete(Unit) },
                        timeoutMs = 10_000L,
                    )
                }
            }
            withTimeout(2_000L) { browserOpened.await() }
            assertTrue(manager.uiState.value.isSigningIn)

            // User leaves app for browser, then returns without completing OAuth
            manager.onAppPaused()
            manager.onAppResumed(gracePeriodMs = 40L)

            val outcome = withTimeout(2_000L) { signInJob.await() }
            assertTrue(
                "Expected CancellationIOException, got $outcome",
                outcome.exceptionOrNull() is CancellationIOException,
            )

            assertFalse(manager.uiState.value.isSigningIn)
            assertNull(manager.uiState.value.lastError)
            assertFalse(coordinator.hasActiveSession())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syncManager_migratesLegacyDesktopClientIdAndClearsSecretWhenSignedOut() = runBlocking {
        val root = tempFolder.newFolder("mgrMigrate")
        val saves = SaveFileManager(root)
        val prefs = PreferencesStore(File(root, "preferences.json"))
        val secretStore = InMemorySyncSecretStore(
            StoredOAuthCredentials(
                clientId = GoogleDriveSyncManager.LEGACY_DESKTOP_CLIENT_ID,
                clientSecret = "FAKE-SECRET-legacy-secret",
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = GoogleDriveSyncManager(
                secretStore = secretStore,
                preferences = prefs,
                romsProvider = { emptyList() },
                saves = saves,
                filesDir = root,
                scope = scope,
                defaultClientId = TEST_ANDROID_CLIENT_ID,
            )
            assertEquals(TEST_ANDROID_CLIENT_ID, manager.uiState.value.clientId)
            assertEquals(TEST_ANDROID_CLIENT_ID, secretStore.load().clientId)
            assertEquals("", secretStore.load().clientSecret)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syncManager_signIn_failsFastWhenClientIdDoesNotMatchRegisteredRedirectScheme() = runBlocking {
        val root = tempFolder.newFolder("mgrSchemeMismatch")
        val saves = SaveFileManager(root)
        val prefs = PreferencesStore(File(root, "preferences.json"))
        val mismatchedClientId = "999999999999-otherclient.apps.googleusercontent.com"
        val secretStore = InMemorySyncSecretStore(
            StoredOAuthCredentials(clientId = mismatchedClientId),
        )
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val browserOpened = AtomicBoolean(false)
        try {
            val manager = GoogleDriveSyncManager(
                secretStore = secretStore,
                preferences = prefs,
                romsProvider = { emptyList() },
                saves = saves,
                filesDir = root,
                scope = scope,
                defaultClientId = TEST_ANDROID_CLIENT_ID,
                registeredRedirectScheme = TEST_SCHEME,
                redirectCoordinator = coordinator,
            )
            try {
                manager.signIn(openBrowser = { browserOpened.set(true) })
                fail("Expected IllegalStateException for unregistered redirect scheme")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains(TEST_SCHEME))
            }
            assertFalse("Browser must not be opened when redirect scheme is unregistered", browserOpened.get())
            assertFalse(manager.uiState.value.isSigningIn)
            assertTrue(manager.uiState.value.lastError!!.contains(TEST_SCHEME))
            assertFalse(coordinator.hasActiveSession())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun buildConfig_redirectSchemeMatchesDefaultClientId() {
        assertEquals(
            GoogleOAuthFlow.deriveRedirectScheme(BuildConfig.DEFAULT_GDRIVE_CLIENT_ID),
            BuildConfig.GDRIVE_REDIRECT_SCHEME,
        )
    }

    private fun parseUrlQuery(rawUrl: String): Map<String, String> {
        val query = URL(rawUrl).query ?: return emptyMap()
        return query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }.toMap()
    }
}

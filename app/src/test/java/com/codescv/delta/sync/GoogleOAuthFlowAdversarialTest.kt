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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URL
import java.net.URLDecoder

/** Adversarial edge cases for the Android OAuth PKCE + custom URI scheme migration. */
class GoogleOAuthFlowAdversarialTest {

    companion object {
        private const val ANDROID_CLIENT_ID =
            "304560912778-7ln54oj60t2v9mt0nqri70hl11joa0v9.apps.googleusercontent.com"
        private const val SCHEME =
            "com.googleusercontent.apps.304560912778-7ln54oj60t2v9mt0nqri70hl11joa0v9"
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun newManager(
        name: String,
        store: SyncSecretStore,
        coordinator: OAuthRedirectCoordinator,
        scope: CoroutineScope,
        server: MockDriveHttpServer? = null,
    ): GoogleDriveSyncManager {
        val root = tempFolder.newFolder(name)
        return GoogleDriveSyncManager(
            secretStore = store,
            preferences = PreferencesStore(File(root, "preferences.json")),
            romsProvider = { emptyList() },
            saves = SaveFileManager(root),
            filesDir = root,
            scope = scope,
            defaultClientId = ANDROID_CLIENT_ID,
            redirectCoordinator = coordinator,
            tokenEndpoint = server?.tokenEndpoint ?: DEFAULT_OAUTH_TOKEN_ENDPOINT,
            revokeEndpoint = server?.revokeEndpoint ?: DEFAULT_OAUTH_REVOKE_ENDPOINT,
            apiBase = server?.apiBase ?: DEFAULT_DRIVE_API_BASE,
            uploadBase = server?.uploadBase ?: DEFAULT_DRIVE_UPLOAD_BASE,
        )
    }

    // ---------------------------------------------------------------------
    // Build/manifest consistency
    // ---------------------------------------------------------------------

    @Test
    fun manifestScheme_matchesSchemeDerivedFromBuildConfigDefaultClientId() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.exists() }.readText()
        val expected = GoogleOAuthFlow.deriveRedirectScheme(BuildConfig.DEFAULT_GDRIVE_CLIENT_ID)
        val placeholderUsed = manifest.contains("android:scheme=\"\${")
        assertTrue(
            "Manifest OAuthRedirectActivity scheme must match the default client ID ($expected) " +
                "or be injected via manifestPlaceholders",
            placeholderUsed || manifest.contains("android:scheme=\"$expected\""),
        )
        assertEquals(SCHEME, expected)
    }

    // ---------------------------------------------------------------------
    // onAppResumed grace period vs. OAuthRedirectActivity delivery races
    // ---------------------------------------------------------------------

    @Test
    fun resumeFiresBeforeRedirectWithinGrace_signInStillSucceeds() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            MockDriveHttpServer(validAccessToken = "acc").use { server ->
                server.onTokenRequest = {
                    200 to """{"access_token":"acc","refresh_token":"ref","expires_in":3600}"""
                }
                val manager = newManager("raceBefore", InMemorySyncSecretStore(), coordinator, scope, server)
                manager.signIn(
                    timeoutMs = 10_000L,
                    openBrowser = { url ->
                        val p = parseUrlQuery(url)
                        manager.onAppPaused()
                        // MainActivity.onResume fires first (e.g. browser task closed before intent dispatch)
                        manager.onAppResumed(gracePeriodMs = 300L)
                        // ...then the redirect arrives inside the grace window.
                        Thread.sleep(50L)
                        assertTrue(coordinator.deliver("${p["redirect_uri"]}?code=c&state=${p["state"]}"))
                    },
                )
                assertTrue(manager.uiState.value.isSignedIn)
                assertFalse(manager.uiState.value.isSigningIn)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun redirectAfterGraceCancel_isRejectedAndDoesNotResurrectSignIn() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val opened = CompletableDeferred<Map<String, String>>()
        try {
            val manager = newManager("raceAfter", InMemorySyncSecretStore(), coordinator, scope)
            val job = async(Dispatchers.IO) {
                runCatching { manager.signIn(timeoutMs = 10_000L, openBrowser = { opened.complete(parseUrlQuery(it)) }) }
            }
            val p = withTimeout(2_000L) { opened.await() }
            manager.onAppPaused()
            manager.onAppResumed(gracePeriodMs = 20L)
            val outcome = withTimeout(2_000L) { job.await() }
            assertTrue(outcome.exceptionOrNull() is CancellationIOException)
            assertFalse(coordinator.deliver("${p["redirect_uri"]}?code=late&state=${p["state"]}"))
            assertFalse(manager.uiState.value.isSigningIn)
            assertFalse(manager.uiState.value.isSignedIn)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pauseResumeWithoutActiveSession_doesNotPoisonNextSignIn() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val opened = CompletableDeferred<Unit>()
        try {
            val manager = newManager("noSession", InMemorySyncSecretStore(), coordinator, scope)
            // Ordinary app backgrounding before any sign-in.
            manager.onAppPaused()
            manager.onAppResumed(gracePeriodMs = 0L)
            val job = async(Dispatchers.IO) {
                runCatching { manager.signIn(timeoutMs = 10_000L, openBrowser = { opened.complete(Unit) }) }
            }
            withTimeout(2_000L) { opened.await() }
            // A resume without an intervening pause (e.g. config change) must not cancel.
            manager.onAppResumed(gracePeriodMs = 0L)
            delay(100L)
            assertTrue(coordinator.hasActiveSession())
            assertTrue(manager.uiState.value.isSigningIn)
            manager.cancelSignIn()
            assertTrue(withTimeout(2_000L) { job.await() }.exceptionOrNull() is CancellationIOException)
        } finally {
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------------
    // Superseded / stale callbacks
    // ---------------------------------------------------------------------

    @Test
    fun staleCallbackFromSupersededAttempt_cannotCompleteNewAttempt() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = CompletableDeferred<Map<String, String>>()
        val second = CompletableDeferred<Map<String, String>>()
        try {
            val manager = newManager("stale", InMemorySyncSecretStore(), coordinator, scope)
            val job1 = async(Dispatchers.IO) {
                runCatching { manager.signIn(timeoutMs = 10_000L, openBrowser = { first.complete(parseUrlQuery(it)) }) }
            }
            val p1 = withTimeout(2_000L) { first.await() }
            val job2 = async(Dispatchers.IO) {
                runCatching { manager.signIn(timeoutMs = 10_000L, openBrowser = { second.complete(parseUrlQuery(it)) }) }
            }
            val p2 = withTimeout(2_000L) { second.await() }
            assertTrue(withTimeout(2_000L) { job1.await() }.exceptionOrNull() is CancellationIOException)
            assertTrue(p1["state"] != p2["state"])
            assertTrue(manager.uiState.value.isSigningIn)

            // Old browser tab completes with the OLD state: must be rejected as CSRF/stale.
            assertTrue(coordinator.deliver("${p1["redirect_uri"]}?code=old&state=${p1["state"]}"))
            val outcome2 = withTimeout(2_000L) { job2.await() }
            val err = outcome2.exceptionOrNull()
            assertTrue("Expected GDriveAuthException, got $err", err is GDriveAuthException)
            assertFalse(manager.uiState.value.isSigningIn)
            assertFalse(manager.uiState.value.isSignedIn)
        } finally {
            scope.cancel()
        }
    }

    // ---------------------------------------------------------------------
    // Legacy Desktop client users
    // ---------------------------------------------------------------------

    @Test
    fun signedInLegacyDesktopUser_keepsIdAndSecretForRefresh_thenSignOutMigrates() = runBlocking {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val forms = mutableListOf<Map<String, String>>()
        val store = InMemorySyncSecretStore(
            StoredOAuthCredentials(
                clientId = GoogleDriveSyncManager.LEGACY_DESKTOP_CLIENT_ID,
                clientSecret = "FAKE-SECRET-legacy",
                accessToken = "old-access",
                refreshToken = "old-refresh",
                expiresAtMs = 1L,
                accountEmail = "legacy@example.com",
            ),
        )
        try {
            MockDriveHttpServer(validAccessToken = "new-access").use { server ->
                server.onTokenRequest = { form ->
                    synchronized(forms) { forms.add(form) }
                    200 to """{"access_token":"new-access","expires_in":3600}"""
                }
                val manager = newManager("legacySignedIn", store, coordinator, scope, server)
                // Signed-in legacy user must NOT be migrated (refresh token is bound to the Desktop client).
                assertTrue(manager.uiState.value.isSignedIn)
                assertEquals(GoogleDriveSyncManager.LEGACY_DESKTOP_CLIENT_ID, store.load().clientId)
                assertEquals("FAKE-SECRET-legacy", store.load().clientSecret)

                val provider = OAuthTokenProvider(
                    clientId = store.load().clientId,
                    clientSecret = store.load().clientSecret,
                    initialAccessToken = "old-access",
                    initialRefreshToken = "old-refresh",
                    initialExpiresAtMs = 1L,
                    tokenEndpoint = server.tokenEndpoint,
                )
                assertEquals("new-access", provider.accessToken())
                val refreshForm = forms.last { it["grant_type"] == "refresh_token" }
                assertEquals(GoogleDriveSyncManager.LEGACY_DESKTOP_CLIENT_ID, refreshForm["client_id"])
                assertEquals("FAKE-SECRET-legacy", refreshForm["client_secret"])

                manager.signOut()
                assertEquals(ANDROID_CLIENT_ID, store.load().clientId)
                assertEquals("", store.load().clientSecret)
                assertFalse(store.load().isSignedIn)
                assertEquals(ANDROID_CLIENT_ID, manager.uiState.value.clientId)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun saveOAuthConfig_onSignedOutUser_dropsAnyResidualSecret() {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = InMemorySyncSecretStore(StoredOAuthCredentials(clientId = ANDROID_CLIENT_ID))
            val manager = newManager("saveCfg", store, coordinator, scope)
            store.save(store.load().copy(clientSecret = "FAKE-SECRET-residual"))
            manager.saveOAuthConfig(ANDROID_CLIENT_ID)
            assertEquals("", store.load().clientSecret)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun editingClientIdWhileLegacySignedIn_doesNotOrphanDesktopRefreshToken() {
        val coordinator = OAuthRedirectCoordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = InMemorySyncSecretStore(
                StoredOAuthCredentials(
                    clientId = GoogleDriveSyncManager.LEGACY_DESKTOP_CLIENT_ID,
                    clientSecret = "FAKE-SECRET-legacy",
                    accessToken = "a",
                    refreshToken = "r",
                    expiresAtMs = 1L,
                ),
            )
            val manager = newManager("editWhileSignedIn", store, coordinator, scope)
            // The Client ID field stays editable while signed in and is saved on a 400ms debounce:
            // user clears / edits the field, then restores the original value.
            manager.saveOAuthConfig("")
            manager.saveOAuthConfig(ANDROID_CLIENT_ID)
            manager.saveOAuthConfig(GoogleDriveSyncManager.LEGACY_DESKTOP_CLIENT_ID)
            val c = store.load()
            if (c.isSignedIn) {
                // Tokens kept => they must still be paired with the Desktop client id + secret.
                assertEquals(GoogleDriveSyncManager.LEGACY_DESKTOP_CLIENT_ID, c.clientId)
                assertEquals("FAKE-SECRET-legacy", c.clientSecret)
            } else {
                assertFalse(manager.uiState.value.isSignedIn)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun parseUrlQuery(rawUrl: String): Map<String, String> {
        val query = URL(rawUrl).query ?: return emptyMap()
        return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}

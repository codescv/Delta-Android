package com.codescv.delta.sync

import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.settings.PreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Coordinates Google Drive OAuth sign-in, token persistence, single-flight coalesced syncing,
 * and debounced auto-sync triggers.
 */
class GoogleDriveSyncManager(
    private val secretStore: SyncSecretStore,
    private val preferences: PreferencesStore,
    private val romsProvider: () -> List<RomEntry>,
    private val saves: SaveFileManager,
    private val filesDir: File,
    private val scope: CoroutineScope,
    private val deviceName: String = "Android",
    private val onLocalFilesUpdated: () -> Unit = {},
    private val clock: SyncClock = SyncClock.SYSTEM,
    private val tokenEndpoint: String = DEFAULT_OAUTH_TOKEN_ENDPOINT,
    private val authEndpoint: String = DEFAULT_OAUTH_AUTH_ENDPOINT,
    private val revokeEndpoint: String = DEFAULT_OAUTH_REVOKE_ENDPOINT,
    private val apiBase: String = DEFAULT_DRIVE_API_BASE,
    private val uploadBase: String = DEFAULT_DRIVE_UPLOAD_BASE,
    private val driveApiFactory: (DriveTokenProvider) -> DriveApi = { provider ->
        HttpGoogleDriveClient(
            tokenProvider = provider,
            apiBase = apiBase,
            uploadBase = uploadBase,
        )
    },
) {
    companion object {
        const val AUTO_SYNC_DEBOUNCE_MS = 30_000L
    }

    private val baselineFile: File get() = File(filesDir, SYNC_STATE_FILE_NAME)

    private val _uiState = MutableStateFlow(initialUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private val flightMutex = Mutex()
    private var activeSync: Deferred<SyncResult>? = null
    @Volatile
    private var cachedTokenProvider: OAuthTokenProvider? = null
    @Volatile
    private var lastAutoSyncAtMs: Long = 0L

    /** Monotonic generation counter incremented on sign-in and sign-out to fence stale callbacks/writes. */
    private val authGeneration = AtomicLong(0L)

    @Volatile
    private var activeLoopback: GoogleOAuthLoopback? = null

    private fun initialUiState(): SyncUiState {
        val creds = secretStore.load()
        return SyncUiState(
            isSignedIn = creds.isSignedIn,
            accountEmail = creds.accountEmail,
            accountName = creds.accountName,
            clientId = creds.clientId,
            hasClientSecret = creds.clientSecret.isNotBlank(),
        )
    }

    fun currentClientSecret(): String = secretStore.load().clientSecret

    fun saveOAuthConfig(clientId: String, clientSecret: String) {
        val trimmedId = clientId.trim()
        val trimmedSecret = clientSecret.trim()
        runCatching {
            val current = secretStore.load()
            if (current.clientId == trimmedId && current.clientSecret == trimmedSecret) return
            secretStore.save(
                current.copy(
                    clientId = trimmedId,
                    clientSecret = trimmedSecret,
                ),
            )
            cachedTokenProvider = null
            _uiState.update {
                it.copy(
                    clientId = trimmedId,
                    hasClientSecret = trimmedSecret.isNotBlank(),
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(lastError = "Could not save OAuth settings: ${redactSecrets(err.message ?: "Keystore error")}")
            }
        }
    }

    suspend fun signIn(openBrowser: (String) -> Unit): SyncAccountInfo {
        val creds = secretStore.load()
        val clientId = creds.clientId.trim()
        val clientSecret = creds.clientSecret.trim()
        if (clientId.isEmpty()) {
            val msg = "Enter a Desktop OAuth Client ID first."
            _uiState.update { it.copy(lastError = msg) }
            throw IllegalStateException(msg)
        }

        _uiState.update { it.copy(isSigningIn = true, lastError = null) }
        val loopback = GoogleOAuthLoopback(
            clientId = clientId,
            clientSecret = clientSecret,
            openBrowser = openBrowser,
            tokenEndpoint = tokenEndpoint,
            authEndpoint = authEndpoint,
            clock = clock,
        )
        activeLoopback = loopback
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                loopback.cancel()
            }
        }
        return try {
            val (tokens, userInfo) = withContext(Dispatchers.IO) {
                val t = loopback.start()
                val info = fetchDriveUserInfo(t.accessToken, apiBase = apiBase)
                t to info
            }
            val updated = StoredOAuthCredentials(
                clientId = clientId,
                clientSecret = clientSecret,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.ifBlank { creds.refreshToken },
                expiresAtMs = tokens.expiresAtMs,
                accountEmail = userInfo.email,
                accountName = userInfo.displayName,
            )
            authGeneration.incrementAndGet()
            secretStore.save(updated)
            cachedTokenProvider = null
            _uiState.update {
                it.copy(
                    isSignedIn = true,
                    accountEmail = updated.accountEmail,
                    accountName = updated.accountName,
                    isSigningIn = false,
                    lastError = null,
                )
            }
            userInfo
        } catch (e: CancellationIOException) {
            _uiState.update { it.copy(isSigningIn = false) }
            throw e
        } catch (e: CancellationException) {
            _uiState.update { it.copy(isSigningIn = false) }
            throw e
        } catch (e: Exception) {
            val msg = redactSecrets(e.message ?: "Google sign-in failed")
            _uiState.update { it.copy(isSigningIn = false, lastError = msg) }
            throw e
        } finally {
            cancelHandle.dispose()
            if (activeLoopback === loopback) activeLoopback = null
            runCatching { loopback.close() }
        }
    }

    fun cancelSignIn() {
        activeLoopback?.cancel()
        activeLoopback = null
        _uiState.update { it.copy(isSigningIn = false) }
    }

    suspend fun signOut() {
        authGeneration.incrementAndGet()
        cancelSignIn()
        val runningSync = flightMutex.withLock {
            val s = activeSync
            activeSync = null
            s
        }
        runningSync?.cancel()
        cachedTokenProvider = null
        val creds = secretStore.load()
        withContext(Dispatchers.IO) {
            val tokenToRevoke = creds.refreshToken.ifBlank { creds.accessToken }
            if (tokenToRevoke.isNotBlank()) {
                revokeOAuthTokenBestEffort(tokenToRevoke, revokeEndpoint = revokeEndpoint)
            }
            runCatching { secretStore.clearTokens() }
            runCatching { baselineFile.delete() }
        }
        _uiState.update {
            it.copy(
                isSignedIn = false,
                accountEmail = "",
                accountName = "",
                isSyncing = false,
                progressMessage = null,
                lastResult = null,
                lastSyncedAtMs = 0L,
                lastError = null,
            )
        }
    }

    /**
     * Triggers a non-blocking background auto-sync if `syncAutoEnabled && isSignedIn`.
     * When [debounce] is true (e.g. app foreground resume), skips if synced within [AUTO_SYNC_DEBOUNCE_MS].
     */
    fun triggerAutoSync(reason: String, debounce: Boolean = false) {
        val prefs = preferences.value
        if (!prefs.syncAutoEnabled) return
        if (!secretStore.load().isSignedIn) return
        val now = clock.nowMs()
        if (debounce && now - lastAutoSyncAtMs < AUTO_SYNC_DEBOUNCE_MS) return
        lastAutoSyncAtMs = now
        scope.launch(Dispatchers.IO) {
            runCatching { syncNow() }
        }
    }

    /**
     * Runs a full sync. Overlapping calls coalesce onto the single in-flight sync.
     */
    suspend fun syncNow(): SyncResult {
        val deferred = flightMutex.withLock {
            val existing = activeSync
            if (existing != null && existing.isActive) {
                existing
            } else {
                lateinit var created: Deferred<SyncResult>
                created = scope.async(Dispatchers.IO) {
                    try {
                        executeSync()
                    } finally {
                        withContext(NonCancellable) {
                            flightMutex.withLock {
                                if (activeSync === created) {
                                    activeSync = null
                                }
                            }
                        }
                    }
                }
                activeSync = created
                created
            }
        }
        return deferred.await()
    }

    private suspend fun executeSync(): SyncResult {
        val gen = authGeneration.get()
        val creds = secretStore.load()
        if (!creds.isSignedIn) {
            val err = "Not signed in to Google Drive"
            _uiState.update { it.copy(isSignedIn = false, isSyncing = false, lastError = err) }
            return SyncResult(errors = listOf(err), syncedAtMs = clock.nowMs())
        }

        _uiState.update {
            it.copy(
                isSyncing = true,
                progressMessage = "Starting sync…",
                lastError = null,
            )
        }

        val prefs = preferences.value
        val options = SyncOptions(
            syncBatterySaves = prefs.syncBatterySaves,
            syncSaveStates = prefs.syncSaveStates,
            conflictPolicy = ConflictPolicy.fromRaw(prefs.syncConflictPolicy),
            deviceName = deviceName,
        )

        return try {
            val tokenProvider = getOrCreateTokenProvider(creds, gen)
            val driveApi = driveApiFactory(tokenProvider)
            val engine = GoogleDriveSyncEngine(
                driveApi = driveApi,
                saveFileManager = saves,
                baselineFile = baselineFile,
                isGameRunning = { rom -> saves.isRunning(rom) },
                clock = clock,
                shouldPersistBaseline = { authGeneration.get() == gen },
            )
            val result = withContext(Dispatchers.IO) {
                engine.syncAll(
                    roms = romsProvider(),
                    options = options,
                    onProgress = { msg ->
                        if (authGeneration.get() == gen) {
                            _uiState.update { it.copy(progressMessage = msg) }
                        }
                    },
                )
            }
            if (authGeneration.get() != gen) {
                return SyncResult(syncedAtMs = clock.nowMs())
            }
            if (result.downloaded > 0) {
                runCatching { onLocalFilesUpdated() }
            }
            lastAutoSyncAtMs = clock.nowMs()
            _uiState.update {
                it.copy(
                    isSyncing = false,
                    progressMessage = null,
                    lastResult = result,
                    lastSyncedAtMs = result.syncedAtMs,
                    lastError = result.errors.firstOrNull(),
                )
            }
            result
        } catch (ce: CancellationException) {
            if (authGeneration.get() == gen) {
                _uiState.update { it.copy(isSyncing = false, progressMessage = null) }
            }
            throw ce
        } catch (authErr: GDriveAuthException) {
            val msg = redactSecrets(authErr.message ?: "Google Drive authentication required")
            if (authGeneration.get() == gen) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        progressMessage = null,
                        lastError = msg,
                    )
                }
            }
            SyncResult(errors = listOf(msg), syncedAtMs = clock.nowMs())
        } catch (e: Exception) {
            val msg = redactSecrets(e.message ?: e.javaClass.simpleName)
            if (authGeneration.get() == gen) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        progressMessage = null,
                        lastError = msg,
                    )
                }
            }
            SyncResult(errors = listOf(msg), syncedAtMs = clock.nowMs())
        }
    }

    private fun getOrCreateTokenProvider(creds: StoredOAuthCredentials, generation: Long): OAuthTokenProvider {
        cachedTokenProvider?.let { return it }
        val provider = OAuthTokenProvider(
            clientId = creds.clientId,
            clientSecret = creds.clientSecret,
            initialAccessToken = creds.accessToken,
            initialRefreshToken = creds.refreshToken,
            initialExpiresAtMs = creds.expiresAtMs,
            tokenEndpoint = tokenEndpoint,
            clock = clock,
            onTokensRefreshed = { newAccess, rotatedRefresh, newExpiresAt ->
                if (authGeneration.get() != generation) return@OAuthTokenProvider
                val latest = secretStore.load()
                if (!latest.isSignedIn) return@OAuthTokenProvider
                secretStore.save(
                    latest.copy(
                        accessToken = newAccess,
                        refreshToken = rotatedRefresh ?: latest.refreshToken,
                        expiresAtMs = newExpiresAt,
                    ),
                )
            },
        )
        cachedTokenProvider = provider
        return provider
    }
}

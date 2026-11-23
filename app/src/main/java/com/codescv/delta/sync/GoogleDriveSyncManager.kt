package com.codescv.delta.sync

import com.codescv.delta.BuildConfig
import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.settings.PreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
import java.util.Locale
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
    private val defaultClientId: String = "",
    private val registeredRedirectScheme: String = BuildConfig.GDRIVE_REDIRECT_SCHEME,
    private val onLocalFilesUpdated: () -> Unit = {},
    private val clock: SyncClock = SyncClock.SYSTEM,
    private val redirectCoordinator: OAuthRedirectCoordinator = OAuthRedirectCoordinator.Default,
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
        const val BROWSER_RETURN_CANCEL_GRACE_MS = 1_200L
        internal const val LEGACY_DESKTOP_CLIENT_ID =
            "304560912778-dr91a3ultmqer7155tmc0oevi84k7bd9.apps.googleusercontent.com"
    }

    private val baselineFile: File get() = File(filesDir, SYNC_STATE_FILE_NAME)

    private val _uiState = MutableStateFlow(initialUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private val flightMutex = Mutex()
    private val engineMutex = Mutex()
    private var activeSync: Deferred<SyncResult>? = null
    private val activeResolutions = HashSet<Deferred<Result<Unit>>>()
    @Volatile
    private var cachedTokenProvider: OAuthTokenProvider? = null
    @Volatile
    private var lastAutoSyncAtMs: Long = 0L

    /** Monotonic generation counter incremented on sign-in and sign-out to fence stale callbacks/writes. */
    private val authGeneration = AtomicLong(0L)

    @Volatile
    private var activeAuthFlow: GoogleOAuthFlow? = null

    private fun initialUiState(): SyncUiState {
        var creds = secretStore.load()
        val seededId = defaultClientId.trim()
        val isLegacyDesktopId = !creds.isSignedIn && creds.clientId.trim() == LEGACY_DESKTOP_CLIENT_ID
        val needId = (creds.clientId.isBlank() || isLegacyDesktopId) && seededId.isNotBlank()
        val clearLegacySecret = !creds.isSignedIn && creds.clientSecret.isNotBlank()
        if (needId || clearLegacySecret) {
            val updated = creds.copy(
                clientId = if (needId) seededId else creds.clientId,
                clientSecret = if (clearLegacySecret) "" else creds.clientSecret,
            )
            runCatching { secretStore.save(updated) }
            creds = updated
        }
        return SyncUiState(
            isSignedIn = creds.isSignedIn,
            accountEmail = creds.accountEmail,
            accountName = creds.accountName,
            clientId = creds.clientId,
        )
    }

    fun saveOAuthConfig(clientId: String) {
        val trimmedId = clientId.trim()
        runCatching {
            val current = secretStore.load()
            if (current.isSignedIn) return
            if (current.clientId == trimmedId && current.clientSecret.isEmpty()) return
            secretStore.save(
                current.copy(
                    clientId = trimmedId,
                    clientSecret = "",
                ),
            )
            cachedTokenProvider = null
            _uiState.update { it.copy(clientId = trimmedId) }
        }.onFailure { err ->
            _uiState.update {
                it.copy(lastError = "Could not save OAuth settings: ${redactSecrets(err.message ?: "Keystore error")}")
            }
        }
    }

    suspend fun signIn(
        timeoutMs: Long = 5 * 60_000L,
        openBrowser: (String) -> Unit,
    ): SyncAccountInfo {
        val creds = secretStore.load()
        val clientId = creds.clientId.trim()
        if (clientId.isEmpty()) {
            val msg = "Enter an Android OAuth Client ID first."
            _uiState.update { it.copy(lastError = msg) }
            throw IllegalStateException(msg)
        }
        val derivedScheme = runCatching { GoogleOAuthFlow.deriveRedirectScheme(clientId) }.getOrElse { err ->
            val msg = redactSecrets(err.message ?: "Invalid Android OAuth Client ID")
            _uiState.update { it.copy(lastError = msg) }
            throw err
        }
        val expectedScheme = registeredRedirectScheme.trim().lowercase(Locale.US)
        if (expectedScheme.isNotEmpty() && derivedScheme != expectedScheme) {
            val msg = "OAuth Client ID does not match this build's registered redirect scheme " +
                "($expectedScheme). Use the app's Android OAuth Client ID or rebuild with DELTA_GDRIVE_CLIENT_ID."
            _uiState.update { it.copy(lastError = msg) }
            throw IllegalStateException(msg)
        }

        _uiState.update { it.copy(isSigningIn = true, lastError = null) }
        val flow = GoogleOAuthFlow(
            clientId = clientId,
            openBrowser = openBrowser,
            redirectCoordinator = redirectCoordinator,
            tokenEndpoint = tokenEndpoint,
            authEndpoint = authEndpoint,
            clock = clock,
        )
        activeAuthFlow?.cancel("Superseded by a new Google sign-in")
        activeAuthFlow = flow
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                flow.cancel()
            }
        }
        return try {
            val tokens = flow.authenticate(timeoutMs = timeoutMs)
            val userInfo = withContext(Dispatchers.IO) {
                fetchDriveUserInfo(tokens.accessToken, apiBase = apiBase)
            }
            val fallbackRefresh = if (creds.clientId.trim() == clientId) creds.refreshToken else ""
            val updated = StoredOAuthCredentials(
                clientId = clientId,
                clientSecret = "",
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.ifBlank { fallbackRefresh },
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
                    clientId = updated.clientId,
                    isSigningIn = false,
                    lastError = null,
                    pendingConflicts = emptyList(),
                    resolvingConflictId = null,
                    conflictPromptDismissed = false,
                )
            }
            userInfo
        } catch (e: CancellationIOException) {
            if (activeAuthFlow === flow) {
                _uiState.update { it.copy(isSigningIn = false) }
            }
            throw e
        } catch (e: CancellationException) {
            if (activeAuthFlow === flow) {
                _uiState.update { it.copy(isSigningIn = false) }
            }
            throw e
        } catch (e: Exception) {
            val msg = redactSecrets(e.message ?: "Google sign-in failed")
            if (activeAuthFlow === flow) {
                _uiState.update { it.copy(isSigningIn = false, lastError = msg) }
            }
            throw e
        } finally {
            cancelHandle.dispose()
            if (activeAuthFlow === flow) activeAuthFlow = null
            runCatching { flow.close() }
        }
    }

    fun cancelSignIn() {
        activeAuthFlow?.cancel()
        activeAuthFlow = null
        redirectCoordinator.cancelActive()
        _uiState.update { it.copy(isSigningIn = false) }
    }

    /** Called from [MainActivity.onPause] so returning without a redirect can cancel a dangling sign-in. */
    fun onAppPaused() {
        redirectCoordinator.markLeftForBrowser()
    }

    /**
     * Called from [MainActivity.onResume]. If the user left Delta for the OAuth browser and
     * returned without [OAuthRedirectActivity] delivering a callback within [gracePeriodMs],
     * cancels the pending sign-in cleanly so the UI does not remain stuck waiting.
     */
    fun onAppResumed(gracePeriodMs: Long = BROWSER_RETURN_CANCEL_GRACE_MS) {
        val pendingSessionId = redirectCoordinator.consumeReturnedFromBrowserSessionId() ?: return
        scope.launch(Dispatchers.IO) {
            if (gracePeriodMs > 0L) delay(gracePeriodMs)
            redirectCoordinator.cancelSessionIfActive(
                sessionId = pendingSessionId,
                reason = "Google sign-in was cancelled",
            )
        }
    }

    suspend fun signOut() {
        authGeneration.incrementAndGet()
        cancelSignIn()
        val (runningSync, runningResolutions) = flightMutex.withLock {
            val s = activeSync
            activeSync = null
            val res = activeResolutions.toList()
            activeResolutions.clear()
            s to res
        }
        runningSync?.cancel()
        runningResolutions.forEach { it.cancel() }
        cachedTokenProvider = null
        val creds = secretStore.load()
        val seededId = defaultClientId.trim()
        val nextClientId = if (creds.clientId.trim() == LEGACY_DESKTOP_CLIENT_ID && seededId.isNotBlank()) {
            seededId
        } else {
            creds.clientId
        }
        withContext(Dispatchers.IO) {
            val tokenToRevoke = creds.refreshToken.ifBlank { creds.accessToken }
            if (tokenToRevoke.isNotBlank()) {
                revokeOAuthTokenBestEffort(tokenToRevoke, revokeEndpoint = revokeEndpoint)
            }
            runCatching {
                secretStore.save(StoredOAuthCredentials(clientId = nextClientId, clientSecret = ""))
            }
            runCatching { baselineFile.delete() }
        }
        _uiState.update {
            it.copy(
                isSignedIn = false,
                accountEmail = "",
                accountName = "",
                clientId = nextClientId,
                isSyncing = false,
                progressMessage = null,
                lastResult = null,
                lastSyncedAtMs = 0L,
                lastError = null,
                pendingConflicts = emptyList(),
                resolvingConflictId = null,
                conflictPromptDismissed = false,
            )
        }
    }

    /** Hides the conflict chooser modal until the next sync or manual reopen from Settings. */
    fun dismissConflictPrompt() {
        _uiState.update { it.copy(conflictPromptDismissed = true) }
    }

    /** Reopens the conflict chooser modal when pending conflicts exist. */
    fun showConflictPrompt() {
        _uiState.update {
            if (it.pendingConflicts.isNotEmpty()) it.copy(conflictPromptDismissed = false) else it
        }
    }

    /**
     * Resolves a single pending [SyncConflict] under [flightMutex] + [engineMutex] + [authGeneration]
     * fencing so it cannot race with [syncNow] or [signOut].
     */
    suspend fun resolveConflict(conflict: SyncConflict, choice: ConflictChoice): Result<Unit> {
        lateinit var deferred: Deferred<Result<Unit>>
        flightMutex.withLock {
            deferred = scope.async(Dispatchers.IO) {
                try {
                    engineMutex.withLock {
                        executeResolveConflict(conflict, choice)
                    }
                } finally {
                    withContext(NonCancellable) {
                        flightMutex.withLock {
                            activeResolutions.remove(deferred)
                        }
                    }
                }
            }
            activeResolutions.add(deferred)
        }
        return deferred.await()
    }

    private suspend fun executeResolveConflict(
        conflict: SyncConflict,
        choice: ConflictChoice,
    ): Result<Unit> {
        val gen = authGeneration.get()
        val creds = secretStore.load()
        if (!creds.isSignedIn) {
            val err = IllegalStateException("Not signed in to Google Drive")
            if (authGeneration.get() == gen) {
                _uiState.update { it.copy(isSignedIn = false, resolvingConflictId = null, lastError = err.message) }
            }
            return Result.failure(err)
        }

        _uiState.update { it.copy(resolvingConflictId = conflict.id, lastError = null) }

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
            val roms = romsProvider()
            withContext(Dispatchers.IO) {
                engine.resolveConflict(
                    conflict = conflict,
                    choice = choice,
                    roms = roms,
                    options = options,
                )
            }
            if (authGeneration.get() != gen) {
                return Result.failure(IllegalStateException("Signed out during conflict resolution"))
            }
            if (choice == ConflictChoice.USE_CLOUD) {
                runCatching { onLocalFilesUpdated() }
            }
            val resolvedRom = roms.firstOrNull { it.id == conflict.romId }
                ?: roms.firstOrNull { GameKey.compute(it) == conflict.gameKey }
            _uiState.update { state ->
                val remaining = state.pendingConflicts.filter { item ->
                    if (item.id == conflict.id) return@filter false
                    // If USE_CLOUD on a battery save invalidated stale local auto-states, prune them too.
                    if (conflict.kind == SyncConflictKind.BATTERY_SAVE &&
                        choice == ConflictChoice.USE_CLOUD &&
                        item.gameKey == conflict.gameKey &&
                        item.kind == SyncConflictKind.SAVE_STATE &&
                        item.stateId != null &&
                        resolvedRom != null &&
                        !saves.stateFile(resolvedRom, item.stateId).isFile
                    ) {
                        return@filter false
                    }
                    true
                }
                state.copy(
                    pendingConflicts = remaining,
                    resolvingConflictId = null,
                    conflictPromptDismissed = if (remaining.isEmpty()) false else state.conflictPromptDismissed,
                    lastSyncedAtMs = clock.nowMs(),
                    lastError = null,
                )
            }
            Result.success(Unit)
        } catch (ce: CancellationException) {
            if (authGeneration.get() == gen) {
                _uiState.update { it.copy(resolvingConflictId = null) }
            }
            throw ce
        } catch (stale: StaleConflictException) {
            val msg = redactSecrets(stale.message ?: "Google Drive copy changed since this conflict was detected.")
            if (authGeneration.get() == gen) {
                _uiState.update { it.copy(resolvingConflictId = null, lastError = msg) }
                // Refresh pending conflicts asynchronously once engineMutex is released.
                scope.launch(Dispatchers.IO) {
                    if (authGeneration.get() == gen) {
                        runCatching { syncNow() }
                    }
                }
            }
            Result.failure(stale)
        } catch (e: Exception) {
            val msg = redactSecrets(e.message ?: e.javaClass.simpleName)
            if (authGeneration.get() == gen) {
                _uiState.update { it.copy(resolvingConflictId = null, lastError = msg) }
            }
            Result.failure(e)
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
                        engineMutex.withLock {
                            executeSync()
                        }
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
                    pendingConflicts = result.pendingConflicts,
                    conflictPromptDismissed = false,
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

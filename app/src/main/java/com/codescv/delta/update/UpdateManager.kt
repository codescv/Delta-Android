package com.codescv.delta.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.codescv.delta.BuildConfig
import com.codescv.delta.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest

/** State of the in-app updater exposed to Compose UI on the main thread. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val latest: ReleaseInfo) : UpdateState

    data class Available(val latest: ReleaseInfo) : UpdateState

    data class Downloading(
        val latest: ReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState {
        val fraction: Float?
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    data class Downloaded(
        val latest: ReleaseInfo,
        val apkFile: File,
    ) : UpdateState

    data class Error(
        val code: UpdateErrorCode,
        val latest: ReleaseInfo? = null,
    ) : UpdateState
}

/**
 * Application-scoped manager for GitHub self-updates of `com.codescv.delta`.
 * All network/archive/disk work runs on [Dispatchers.IO]; state mutations run on `Main.immediate`
 * guarded by [generation] so cancelled jobs never overwrite newer state.
 */
class UpdateManager private constructor(
    private val appContext: Context,
    private val updater: AppUpdater = AppUpdater(),
) {
    /** Raw installed version name for UI display (e.g. `2.0.5` or `2.0.5-DEBUG`). */
    val currentVersion: String = BuildConfig.VERSION_NAME

    /** Base version without `-DEBUG` used when comparing against release tags. */
    val comparableVersion: String = SemanticVersion.stripDebugSuffix(BuildConfig.VERSION_NAME) ?: BuildConfig.VERSION_NAME

    private val currentVersionCode: Long =
        getInstalledPackageInfo(withSignatures = false)
            ?.let { PackageInfoCompat.getLongVersionCode(it) }
            ?: BuildConfig.VERSION_CODE.toLong()

    private val prefs: SharedPreferences = prefs(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    /**
     * Stack of composable dialog hosts (see [UpdateDialogHost]); only the most recently attached
     * host renders the dialog so the root host and the Settings row never show it twice.
     */
    private val _dialogHosts = MutableStateFlow<List<Any>>(emptyList())
    val dialogHosts: StateFlow<List<Any>> = _dialogHosts.asStateFlow()

    val busy: Boolean
        get() = _state.value is UpdateState.Checking || _state.value is UpdateState.Downloading

    private val updatesDir = File(appContext.cacheDir, UPDATES_DIR)

    private var job: Job? = null
    private var cancelToken: CancelToken? = null
    private var generation: Int = 0
    private var autoCheckDone: Boolean = false
    private var pendingInstallAfterPermission: Boolean = false
    private var lastReady: UpdateState.Downloaded? = null
    private var hostActivity: WeakReference<Activity>? = null

    init {
        job = scope.launch { restoreDownloaded() }
    }

    fun attachDialogHost(token: Any) {
        _dialogHosts.value = _dialogHosts.value.filter { it !== token } + token
    }

    fun detachDialogHost(token: Any) {
        _dialogHosts.value = _dialogHosts.value.filter { it !== token }
    }

    fun showDialog() {
        _dialogVisible.value = true
    }

    fun dismissDialog() {
        if (_state.value !is UpdateState.Downloading) {
            _dialogVisible.value = false
        }
    }

    /**
     * Silent launch check, throttled to once per [AUTO_CHECK_INTERVAL_MS] (persisted in
     * SharedPreferences `delta_update`) and once per process.
     */
    fun autoCheckIfDue(nowMs: Long = System.currentTimeMillis()) {
        if (autoCheckDone) return
        val last = prefs.getLong(PREF_LAST_AUTO_CHECK_MS, 0L)
        if (!shouldAutoCheck(nowMs, last)) {
            autoCheckDone = true
            return
        }
        prefs.edit().putLong(PREF_LAST_AUTO_CHECK_MS, nowMs).apply()
        checkForUpdates(silent = true)
    }

    /**
     * Checks GitHub for a newer release.
     * When [silent] is true, runs at most once per process and suppresses UpToDate/Error states.
     */
    fun checkForUpdates(silent: Boolean = false) {
        if (busy) return
        if (silent) {
            if (autoCheckDone) return
            autoCheckDone = true
        }
        val prevJob = job
        val gen = ++generation
        val token = CancelToken().also { cancelToken = it }
        if (!silent) {
            lastReady = _state.value as? UpdateState.Downloaded
            _state.value = UpdateState.Checking
        }
        job =
            scope.launch {
                try {
                    prevJob?.join()
                    val prevReady = (_state.value as? UpdateState.Downloaded) ?: lastReady
                    lastReady = prevReady
                    if (gen != generation) return@launch
                    if (!silent) _state.value = UpdateState.Checking

                    val result =
                        withContext(Dispatchers.IO) {
                            updater.checkLatestRelease(comparableVersion, token)
                        }
                    if (gen != generation) return@launch

                    val latest = result.latest
                    if (!result.updateAvailable) {
                        _state.value =
                            if (silent) {
                                prevReady?.takeIf { it.apkFile.isFile } ?: UpdateState.Idle
                            } else {
                                UpdateState.UpToDate(latest)
                            }
                    } else {
                        val readyFile = withContext(Dispatchers.IO) { existingDownload(latest) }
                        if (gen != generation) return@launch
                        _state.value =
                            if (readyFile != null) {
                                UpdateState.Downloaded(latest, readyFile)
                            } else {
                                UpdateState.Available(latest)
                            }
                        _dialogVisible.value = true
                    }
                } catch (e: UpdateException) {
                    if (gen != generation) return@launch
                    val wasCancelled = e.code == UpdateErrorCode.CANCELLED
                    val prevReady = lastReady?.takeIf { it.apkFile.isFile }
                    if (prevReady != null) {
                        if (!silent && !wasCancelled) {
                            Toast.makeText(
                                appContext,
                                appContext.getString(
                                    R.string.update_status_error,
                                    appContext.getString(errorStringRes(e.code)),
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        _state.value = prevReady
                    } else if (silent || wasCancelled) {
                        _state.value = UpdateState.Idle
                    } else {
                        _state.value = UpdateState.Error(e.code)
                    }
                }
            }
    }

    /** Downloads the APK for the current release and triggers installation once validated. */
    fun downloadAndInstall(activity: Activity) {
        if (busy) return
        val release = currentRelease() ?: return
        val asset = release.apkAsset
        if (asset == null) {
            _state.value = UpdateState.Error(UpdateErrorCode.NO_APK, release)
            return
        }
        hostActivity = WeakReference(activity)
        pendingInstallAfterPermission = false
        val gen = ++generation
        val token = CancelToken().also { cancelToken = it }
        val dest = apkFileFor(release)
        _state.value = UpdateState.Downloading(release, 0L, asset.sizeBytes)
        val prevJob = job
        job =
            scope.launch {
                try {
                    prevJob?.join()
                    val downloaded =
                        withContext(Dispatchers.IO) {
                            updater.downloadRelease(
                                release = release,
                                destFile = dest,
                                cancelToken = token,
                                validator = ::validateApkOrThrow,
                            ) { done, total ->
                                scope.launch {
                                    if (gen == generation && _state.value is UpdateState.Downloading) {
                                        _state.value = UpdateState.Downloading(release, done, total)
                                    }
                                }
                            }
                        }
                    if (gen != generation || _state.value !is UpdateState.Downloading) return@launch
                    val readyState = UpdateState.Downloaded(release, downloaded)
                    lastReady = readyState
                    _state.value = readyState
                    activeActivity(activity)?.let { openInstaller(it, release, downloaded) }
                } catch (e: UpdateException) {
                    if (gen != generation || _state.value !is UpdateState.Downloading) return@launch
                    _state.value =
                        if (e.code == UpdateErrorCode.CANCELLED) {
                            UpdateState.Available(release)
                        } else {
                            UpdateState.Error(e.code, release)
                        }
                }
            }
    }

    /** Cancels an in-flight check or download and clears any pending install flag. */
    fun cancel() {
        val current = _state.value
        pendingInstallAfterPermission = false
        generation++
        cancelToken?.cancel()
        job?.cancel()
        _state.value =
            when (current) {
                is UpdateState.Downloading -> UpdateState.Available(current.latest)
                UpdateState.Checking -> lastReady?.takeIf { it.apkFile.isFile } ?: UpdateState.Idle
                else -> current
            }
    }

    /** Re-validates the downloaded APK on IO and opens the system installer (or permission screen). */
    fun installApk(activity: Activity) {
        val downloadedState = _state.value as? UpdateState.Downloaded ?: return
        hostActivity = WeakReference(activity)
        val gen = generation
        scope.launch {
            val validationError =
                withContext(Dispatchers.IO) {
                    runCatching { validateApkOrThrow(downloadedState.apkFile) }
                        .exceptionOrNull()
                        ?.let { (it as? UpdateException)?.code ?: UpdateErrorCode.CORRUPT }
                        ?.also { downloadedState.apkFile.delete() }
                }
            if (gen != generation || _state.value != downloadedState) return@launch
            if (validationError != null) {
                pendingInstallAfterPermission = false
                lastReady = null
                _state.value =
                    UpdateState.Error(
                        validationError,
                        downloadedState.latest.takeIf { it.apkAsset != null },
                    )
                return@launch
            }
            activeActivity(activity)?.let { openInstaller(it, downloadedState.latest, downloadedState.apkFile) }
        }
    }

    /**
     * Resumes pending APK installation after returning from Android's "Install unknown apps"
     * settings screen if and only if permission was granted (never loops if user backed out).
     */
    fun onHostResume(activity: Activity) {
        hostActivity = WeakReference(activity)
        if (
            pendingInstallAfterPermission &&
            _state.value is UpdateState.Downloaded &&
            activity.packageManager.canRequestPackageInstalls()
        ) {
            pendingInstallAfterPermission = false
            installApk(activity)
        }
    }

    private fun openInstaller(
        activity: Activity,
        release: ReleaseInfo,
        apkFile: File,
    ) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallAfterPermission = true
            Toast.makeText(
                activity,
                activity.getString(R.string.update_allow_unknown_sources),
                Toast.LENGTH_LONG,
            ).show()
            // Launched from Activity WITHOUT FLAG_ACTIVITY_NEW_TASK so Back returns directly to Delta.
            val settingsIntent =
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
            try {
                activity.startActivity(settingsIntent)
            } catch (_: Exception) {
                val fallback = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                runCatching { activity.startActivity(fallback) }.onFailure {
                    pendingInstallAfterPermission = false
                    _state.value = UpdateState.Error(UpdateErrorCode.INSTALLER, release)
                }
            }
            return
        }

        pendingInstallAfterPermission = false
        try {
            val uri =
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    apkFile,
                )
            val installIntent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(installIntent)
        } catch (_: Exception) {
            _state.value = UpdateState.Error(UpdateErrorCode.INSTALLER, release)
        }
    }

    private fun activeActivity(fallback: Activity): Activity? =
        hostActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: fallback.takeUnless { it.isFinishing || it.isDestroyed }

    private fun currentRelease(): ReleaseInfo? =
        when (val s = _state.value) {
            is UpdateState.Available -> s.latest
            is UpdateState.Downloading -> s.latest
            is UpdateState.Downloaded -> s.latest
            is UpdateState.Error -> s.latest
            is UpdateState.UpToDate -> s.latest
            UpdateState.Idle, UpdateState.Checking -> null
        }

    private fun apkFileFor(release: ReleaseInfo): File {
        val safeVersion = release.version.replace(UNSAFE_VERSION_CHARS, "_")
        return File(updatesDir, "delta-android-$safeVersion.apk")
    }

    private fun existingDownload(release: ReleaseInfo): File? {
        val file = apkFileFor(release)
        if (!file.isFile) return null
        val expected = release.apkAsset?.sizeBytes ?: -1L
        if (expected > 0 && file.length() != expected) {
            file.delete()
            return null
        }
        val expectedSha = runCatching { updater.fetchExpectedSha256(release) }.getOrElse {
            // Cannot confirm the checksum right now; force a fresh, verified download.
            return null
        }
        if (expectedSha != null && runCatching { AppUpdater.sha256Hex(file) }.getOrNull() != expectedSha) {
            file.delete()
            return null
        }
        return if (runCatching { validateApkOrThrow(file) }.isSuccess) {
            file
        } else {
            file.delete()
            null
        }
    }

    /**
     * Validates that [apk] is an installable archive of `com.codescv.delta` with a strictly higher
     * `versionCode` and a signing-certificate digest set equal to the running app's. Throws
     * [UpdateException] on failure (fails closed when certificates cannot be read).
     */
    private fun validateApkOrThrow(apk: File) {
        if (!apk.isFile) {
            throw UpdateException(UpdateErrorCode.CORRUPT, "APK file missing")
        }
        val archiveInfo =
            getArchivePackageInfo(apk.path, withSignatures = true)
                ?: throw UpdateException(UpdateErrorCode.CORRUPT, "Failed to parse APK archive")
        val installedInfo = getInstalledPackageInfo(withSignatures = true)
        verifyArchive(
            installedPackage = appContext.packageName,
            installedVersionCode = currentVersionCode,
            installedCertDigests = installedInfo?.let { certDigests(extractSignerBytes(it)) }.orEmpty(),
            archivePackage = archiveInfo.packageName,
            archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo),
            archiveCertDigests = certDigests(extractSignerBytes(archiveInfo)),
        )
    }

    private fun getArchivePackageInfo(
        path: String,
        withSignatures: Boolean,
    ): PackageInfo? {
        val pm = appContext.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val flags = if (withSignatures) PackageManager.GET_SIGNING_CERTIFICATES.toLong() else 0L
                pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                val flags =
                    when {
                        !withSignatures -> 0
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
                        else -> PackageManager.GET_SIGNATURES
                    }
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(path, flags)
            }
        }.getOrNull()
    }

    private fun getInstalledPackageInfo(withSignatures: Boolean): PackageInfo? {
        val pm = appContext.packageManager
        val pkg = appContext.packageName
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val flags = if (withSignatures) PackageManager.GET_SIGNING_CERTIFICATES.toLong() else 0L
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                val flags =
                    when {
                        !withSignatures -> 0
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
                        else -> PackageManager.GET_SIGNATURES
                    }
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, flags)
            }
        }.getOrNull()
    }

    private fun extractSignerBytes(info: PackageInfo): List<ByteArray> {
        val signers: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
                val signingInfo = info.signingInfo!!
                if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory ?: signingInfo.apkContentsSigners
                }
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
        return signers?.map { it.toByteArray() }.orEmpty()
    }

    /**
     * Cleans up orphan `.tmp` files and stale/invalid APKs in `cacheDir/updates`, keeping the
     * newest valid APK so process death during Unknown Sources permission grant recovers cleanly.
     */
    private suspend fun restoreDownloaded() {
        val restored =
            withContext(Dispatchers.IO) {
                val files = updatesDir.listFiles().orEmpty()
                files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
                var best: Pair<File, PackageInfo>? = null
                for (file in files.filter { it.isFile && it.name.endsWith(".apk") }) {
                    if (runCatching { validateApkOrThrow(file) }.isFailure) {
                        file.delete()
                        continue
                    }
                    val info = getArchivePackageInfo(file.path, withSignatures = false) ?: continue
                    val bestInfo = best?.second
                    if (
                        bestInfo == null ||
                        PackageInfoCompat.getLongVersionCode(info) > PackageInfoCompat.getLongVersionCode(bestInfo)
                    ) {
                        best?.first?.delete()
                        best = file to info
                    } else {
                        file.delete()
                    }
                }
                best
            } ?: return

        val (file, info) = restored
        val rawVersion =
            info.versionName?.let { SemanticVersion.parse(it)?.toString() ?: it }
                ?: file.name.removePrefix("delta-android-").removeSuffix(".apk")
        val downloadedState =
            UpdateState.Downloaded(
                ReleaseInfo(
                    tagName = "v$rawVersion",
                    version = rawVersion,
                    title = "v$rawVersion",
                    body = "",
                    publishedAt = null,
                    htmlUrl = ReleaseJson.DEFAULT_RELEASE_HTML_URL,
                    apkAsset = null,
                ),
                file,
            )
        lastReady = downloadedState
        if (_state.value == UpdateState.Idle) {
            _state.value = downloadedState
            _dialogVisible.value = true
        }
    }

    companion object {
        const val EXPECTED_PACKAGE = "com.codescv.delta"
        const val PREFS_NAME = "delta_update"
        const val PREF_LAST_AUTO_CHECK_MS = "last_auto_check_ms"
        const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val UPDATES_DIR = "updates"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private val UNSAFE_VERSION_CHARS = Regex("[^0-9A-Za-z._-]")

        @Volatile
        private var instance: UpdateManager? = null

        fun get(context: Context): UpdateManager =
            instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** True when no silent check ran in the last [intervalMs] (or the clock went backwards). */
        fun shouldAutoCheck(
            nowMs: Long,
            lastCheckMs: Long,
            intervalMs: Long = AUTO_CHECK_INTERVAL_MS,
        ): Boolean = lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs

        /** Lowercase hex SHA-256 of each DER-encoded signing certificate. */
        fun certDigests(rawCerts: List<ByteArray>): Set<String> =
            rawCerts.mapTo(LinkedHashSet()) { raw ->
                MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }

        /** Order-insensitive byte-equality comparison of signing certificates. */
        fun signaturesMatch(
            installedCerts: List<ByteArray>,
            archiveCerts: List<ByteArray>,
        ): Boolean {
            if (installedCerts.isEmpty() || archiveCerts.isEmpty()) return false
            return certDigests(installedCerts) == certDigests(archiveCerts)
        }

        /**
         * Pure install gate: the archive must be [EXPECTED_PACKAGE] (and equal to the running package),
         * have `versionCode > installed`, and carry exactly the running app's signing-certificate digests.
         * Fails closed when either digest set is empty.
         */
        fun verifyArchive(
            installedPackage: String,
            installedVersionCode: Long,
            installedCertDigests: Set<String>,
            archivePackage: String?,
            archiveVersionCode: Long,
            archiveCertDigests: Set<String>,
        ) {
            if (archivePackage != EXPECTED_PACKAGE || archivePackage != installedPackage) {
                throw UpdateException(
                    UpdateErrorCode.WRONG_PACKAGE,
                    "Expected package $EXPECTED_PACKAGE, got $archivePackage",
                )
            }
            if (archiveVersionCode <= installedVersionCode) {
                throw UpdateException(
                    UpdateErrorCode.NOT_NEWER,
                    "Archive versionCode $archiveVersionCode <= installed $installedVersionCode",
                )
            }
            if (archiveCertDigests.isEmpty() || installedCertDigests.isEmpty()) {
                throw UpdateException(UpdateErrorCode.SIGNATURE_MISMATCH, "Signing certificates unavailable")
            }
            if (archiveCertDigests != installedCertDigests) {
                throw UpdateException(
                    UpdateErrorCode.SIGNATURE_MISMATCH,
                    "Archive signing certificate does not match installed app",
                )
            }
        }

        fun errorStringRes(code: UpdateErrorCode): Int =
            when (code) {
                UpdateErrorCode.NETWORK -> R.string.update_error_network
                UpdateErrorCode.TIMEOUT -> R.string.update_error_timeout
                UpdateErrorCode.HTTP -> R.string.update_error_http
                UpdateErrorCode.RATE_LIMITED -> R.string.update_error_rate_limited
                UpdateErrorCode.NOT_FOUND -> R.string.update_error_not_found
                UpdateErrorCode.INVALID_RESPONSE -> R.string.update_error_invalid_response
                UpdateErrorCode.INSECURE_REDIRECT -> R.string.update_error_insecure_redirect
                UpdateErrorCode.TOO_MANY_REDIRECTS -> R.string.update_error_too_many_redirects
                UpdateErrorCode.CORRUPT -> R.string.update_error_corrupt
                UpdateErrorCode.WRONG_PACKAGE -> R.string.update_error_wrong_package
                UpdateErrorCode.NOT_NEWER -> R.string.update_error_not_newer
                UpdateErrorCode.SIGNATURE_MISMATCH -> R.string.update_error_signature_mismatch
                UpdateErrorCode.NO_APK -> R.string.update_error_no_apk
                UpdateErrorCode.INSTALLER -> R.string.update_error_installer
                UpdateErrorCode.CANCELLED -> R.string.update_action_cancel
            }
    }
}

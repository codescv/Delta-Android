package com.codescv.delta.sync

import com.codescv.delta.library.RomEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
const val DEFAULT_OAUTH_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
const val DEFAULT_OAUTH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
const val DEFAULT_OAUTH_REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"
const val DEFAULT_DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
const val DEFAULT_DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
const val DELTA_SYNC_ROOT_FOLDER = "Delta Sync"
const val GAMES_FOLDER_NAME = "games"
const val STATES_FOLDER_NAME = "states"
const val SAVE_RAM_REMOTE_NAME = "save.srm"
const val META_REMOTE_NAME = "meta.json"
const val STATES_INDEX_REMOTE_NAME = "states_index.json"
const val SYNC_STATE_FILE_NAME = "gdrive_sync_state.json"

internal val syncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    coerceInputValues = true
}

internal val syncPrettyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    coerceInputValues = true
}

fun interface SyncClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = SyncClock { System.currentTimeMillis() }
    }
}

enum class ConflictPolicy(val raw: String, val title: String) {
    ASK("ask", "Ask Every Time"),
    NEWEST_WINS("newestWins", "Newest Wins"),
    PREFER_LOCAL("preferLocal", "Prefer Device"),
    PREFER_CLOUD("preferCloud", "Prefer Cloud");

    companion object {
        fun fromRaw(raw: String?): ConflictPolicy =
            entries.firstOrNull { it.raw == raw } ?: ASK
    }
}

enum class SyncConflictKind {
    BATTERY_SAVE,
    SAVE_STATE,
}

enum class ConflictChoice {
    KEEP_LOCAL,
    USE_CLOUD,
}

data class SyncConflict(
    val id: String,
    val gameKey: String,
    val gameName: String,
    val systemId: String,
    val romId: String,
    val kind: SyncConflictKind,
    val stateId: String? = null,
    val stateLabel: String? = null,
    val localMtimeMs: Long,
    val localSizeBytes: Long,
    val remoteMtimeMs: Long,
    val remoteSizeBytes: Long,
    val remoteDeviceName: String? = null,
    val remoteFileId: String,
    val remoteMd5: String? = null,
    val remoteParentFolderId: String? = null,
    val remoteGameFolderId: String? = null,
)

/**
 * Thrown when [GoogleDriveSyncEngine.resolveConflict] detects that the remote Google Drive copy
 * changed or was replaced after the conflict was originally detected, preventing silent overwrite
 * of an unseen cloud version.
 */
class StaleConflictException(
    message: String = "Google Drive copy changed since this conflict was detected. Sync again to review the latest version.",
) : IOException(message)

/**
 * Cross-platform game key matching iOS Delta 100%:
 * - `"${systemId.lowercase()}_${"%08x".format(crc32 and 0xFFFFFFFFL)}"` when `crc32 != null && crc32 != 0L`
 * - Fallback when `crc32 == null || crc32 == 0L`: `"${systemId.lowercase()}_${sanitizedRomStem}"` (max 64 chars).
 */
object GameKey {
    const val MAX_LENGTH = 64

    fun compute(rom: RomEntry): String = compute(rom.systemId, rom.crc32, rom.baseName)

    fun compute(systemId: String, crc32: Long?, romBaseName: String): String {
        val sys = sanitizeSegment(systemId).ifEmpty { "unknown" }
        if (crc32 != null && crc32 != 0L) {
            return "${sys}_${"%08x".format(Locale.US, crc32 and 0xFFFFFFFFL)}"
        }
        val prefix = "${sys}_"
        val maxStemLen = (MAX_LENGTH - prefix.length).coerceAtLeast(1)
        val rawStem = sanitizeSegment(romBaseName).ifEmpty { "game" }
        val stem = rawStem.take(maxStemLen).trimEnd('_').ifEmpty { "game".take(maxStemLen) }
        return "$prefix$stem"
    }

    internal fun sanitizeSegment(raw: String): String {
        val sb = StringBuilder(raw.length)
        var prevUnderscore = false
        for (ch in raw.lowercase(Locale.US)) {
            if (ch in 'a'..'z' || ch in '0'..'9' || ch == '-') {
                sb.append(ch)
                prevUnderscore = false
            } else {
                if (!prevUnderscore && sb.isNotEmpty()) {
                    sb.append('_')
                    prevUnderscore = true
                }
            }
        }
        return sb.toString().trim('_')
    }
}

@Serializable
data class RemoteGameMeta(
    val schemaVersion: Int = 1,
    val gameKey: String,
    val gameName: String,
    val systemId: String,
    val crc32: String,
    val updatedAtMs: Long,
    val updatedByDevice: String,
)

@Serializable
data class RemoteSaveStateEntry(
    val id: String,
    val kind: String = "general",
    val name: String? = null,
    val locked: Boolean = false,
    val createdAtMs: Long = 0L,
    val modifiedAtMs: Long = createdAtMs,
    val coreId: String = "unknown",
    val systemId: String = "",
)

@Serializable
data class SyncBaselineEntry(
    val driveFileId: String,
    val localMd5: String,
    val remoteMd5: String,
    val localMtimeMs: Long,
    val remoteModifiedTimeMs: Long,
    val lastSyncedAtMs: Long,
)

@Serializable
data class SyncBaselineState(
    val rootFolderId: String? = null,
    val files: Map<String, SyncBaselineEntry> = emptyMap(),
)

data class SyncAccountInfo(
    val email: String,
    val displayName: String,
)

data class SyncOptions(
    val syncBatterySaves: Boolean = true,
    val syncSaveStates: Boolean = true,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.ASK,
    val deviceName: String = "Android",
)

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val conflicts: Int = 0,
    val skippedRunningGames: Int = 0,
    val pendingConflicts: List<SyncConflict> = emptyList(),
    val errors: List<String> = emptyList(),
    val syncedAtMs: Long = 0L,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()

    fun summaryText(): String = when {
        hasErrors && uploaded == 0 && downloaded == 0 && pendingConflicts.isEmpty() -> errors.first()
        pendingConflicts.isNotEmpty() && uploaded == 0 && downloaded == 0 ->
            if (pendingConflicts.size == 1) "1 conflict needs review" else "${pendingConflicts.size} conflicts need review"
        pendingConflicts.isNotEmpty() ->
            "Uploaded $uploaded · Downloaded $downloaded · ${pendingConflicts.size} conflict(s)"
        uploaded == 0 && downloaded == 0 -> "Up to date"
        else -> "Uploaded $uploaded · Downloaded $downloaded"
    }
}

data class SyncUiState(
    val isSignedIn: Boolean = false,
    val accountEmail: String = "",
    val accountName: String = "",
    val clientId: String = "",
    val isSigningIn: Boolean = false,
    val isSyncing: Boolean = false,
    val progressMessage: String? = null,
    val lastResult: SyncResult? = null,
    val lastSyncedAtMs: Long = 0L,
    val lastError: String? = null,
    val pendingConflicts: List<SyncConflict> = emptyList(),
    val resolvingConflictId: String? = null,
    val conflictPromptDismissed: Boolean = false,
)

internal fun md5Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("MD5").digest(bytes)
    return digest.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
}

internal fun md5Hex(file: File): String {
    val md = MessageDigest.getInstance("MD5")
    val buf = ByteArray(64 * 1024)
    FileInputStream(file).use { input ->
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
}

internal fun urlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun escapeDriveQueryLiteral(value: String): String =
    value.replace("\\", "\\\\").replace("'", "\\'")

internal fun parseRfc3339Ms(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

internal fun formatRfc3339(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

private val SECRET_QUERY_OR_JSON_REGEX = Regex(
    """(?i)(access_token|refresh_token|client_secret|code_verifier|Authorization)\s*([=:])\s*([^\s&",}]+)""",
)

internal fun redactSecrets(input: String): String =
    SECRET_QUERY_OR_JSON_REGEX.replace(input) { m ->
        "${m.groupValues[1]}${m.groupValues[2]}<redacted>"
    }

private val SAFE_STATE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

internal fun isSafeStateId(id: String): Boolean = SAFE_STATE_ID_REGEX.matches(id)

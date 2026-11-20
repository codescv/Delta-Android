package com.codescv.delta.sync

import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 3-way Google Drive sync engine matching Delta iOS 100%.
 *
 * Remote layout under My Drive:
 * ```
 * Delta Sync/
 *   games/
 *     <gameKey>/
 *       save.srm
 *       meta.json
 *       states_index.json
 *       states/
 *         <stateId>.svs
 *         <stateId>.png
 * ```
 */
class GoogleDriveSyncEngine(
    private val driveApi: DriveApi,
    private val saveFileManager: SaveFileManager,
    private val baselineFile: File,
    private val isGameRunning: (RomEntry) -> Boolean = { saveFileManager.isRunning(it) },
    private val clock: SyncClock = SyncClock.SYSTEM,
    private val shouldPersistBaseline: () -> Boolean = { true },
) {
    private enum class FileSyncOutcome {
        NONE,
        SKIPPED,
        UPLOADED,
        DOWNLOADED,
        UPLOADED_CONFLICT,
        DOWNLOADED_CONFLICT,
        SKIPPED_RUNNING,
        SKIPPED_RUNNING_CONFLICT,
        LOCAL_DELETED,
    }

    suspend fun syncAll(
        roms: List<RomEntry>,
        options: SyncOptions = SyncOptions(),
        onProgress: (String) -> Unit = {},
    ): SyncResult {
        val initialState = loadBaselineState()
        val baseline = initialState.files.toMutableMap()
        var activeRootFolderId: String? = initialState.rootFolderId
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var conflicts = 0
        var skippedRunningGames = 0
        val errors = mutableListOf<String>()

        try {
            onProgress("Connecting to Google Drive…")
            val rootFolder = driveApi.findOrCreateFolder(DELTA_SYNC_ROOT_FOLDER, parentId = null)
            // Account switch guard: if the Drive root folder ID changed, old baseline entries
            // belong to a different account and must be cleared so local saves are never silently overwritten.
            if (activeRootFolderId != null && activeRootFolderId != rootFolder.id) {
                baseline.clear()
            }
            activeRootFolderId = rootFolder.id

            val gamesFolder = driveApi.findOrCreateFolder(GAMES_FOLDER_NAME, parentId = rootFolder.id)
            val remoteGameFolders = indexByNameLatest(
                driveApi.listChildren(gamesFolder.id).filter { it.isFolder },
            )

            // Deduplicate by gameKey to avoid double-syncing identical ROMs.
            val uniqueRoms = roms.distinctBy { GameKey.compute(it) }
            val total = uniqueRoms.size

            uniqueRoms.forEachIndexed { idx, rom ->
                val gameKey = GameKey.compute(rom)
                val existingRemoteGameFolder = remoteGameFolders[gameKey]
                val hasLocalBattery = options.syncBatterySaves && saveFileManager.hasSaveRam(rom)
                val hasLocalStates = options.syncSaveStates && saveFileManager.listStates(rom).isNotEmpty()
                if (!hasLocalBattery && !hasLocalStates && existingRemoteGameFolder == null) {
                    return@forEachIndexed
                }

                onProgress("Syncing ${rom.title} (${idx + 1}/$total)…")
                try {
                    val gameStats = syncGame(
                        rom = rom,
                        gameKey = gameKey,
                        gamesFolderId = gamesFolder.id,
                        existingRemoteGameFolder = existingRemoteGameFolder,
                        baseline = baseline,
                        options = options,
                    )
                    uploaded += gameStats.uploaded
                    downloaded += gameStats.downloaded
                    skipped += gameStats.skipped
                    conflicts += gameStats.conflicts
                    skippedRunningGames += gameStats.skippedRunningGames
                } catch (authErr: GDriveAuthException) {
                    throw authErr
                } catch (e: Exception) {
                    errors += "${rom.title}: ${redactSecrets(e.message ?: e.javaClass.simpleName)}"
                }
            }
        } finally {
            if (shouldPersistBaseline()) {
                saveBaselineState(SyncBaselineState(rootFolderId = activeRootFolderId, files = baseline))
            }
        }

        return SyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            skipped = skipped,
            conflicts = conflicts,
            skippedRunningGames = skippedRunningGames,
            errors = errors,
            syncedAtMs = clock.nowMs(),
        )
    }

    private data class GameSyncStats(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val skipped: Int = 0,
        val conflicts: Int = 0,
        val skippedRunningGames: Int = 0,
    )

    private suspend fun syncGame(
        rom: RomEntry,
        gameKey: String,
        gamesFolderId: String,
        existingRemoteGameFolder: DriveFile?,
        baseline: MutableMap<String, SyncBaselineEntry>,
        options: SyncOptions,
    ): GameSyncStats {
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var conflicts = 0
        var skippedRunning = false
        var anyUploadedForGame = false

        val gameFolder = existingRemoteGameFolder
            ?: driveApi.findOrCreateFolder(gameKey, parentId = gamesFolderId)
        val gameChildren = indexByNameLatest(driveApi.listChildren(gameFolder.id))

        fun recordOutcome(outcome: FileSyncOutcome) {
            when (outcome) {
                FileSyncOutcome.NONE -> Unit
                FileSyncOutcome.SKIPPED -> skipped++
                FileSyncOutcome.UPLOADED -> {
                    uploaded++
                    anyUploadedForGame = true
                }
                FileSyncOutcome.DOWNLOADED -> downloaded++
                FileSyncOutcome.UPLOADED_CONFLICT -> {
                    uploaded++
                    conflicts++
                    anyUploadedForGame = true
                }
                FileSyncOutcome.DOWNLOADED_CONFLICT -> {
                    downloaded++
                    conflicts++
                }
                FileSyncOutcome.SKIPPED_RUNNING -> skippedRunning = true
                FileSyncOutcome.SKIPPED_RUNNING_CONFLICT -> {
                    skippedRunning = true
                    conflicts++
                }
                FileSyncOutcome.LOCAL_DELETED -> Unit
            }
        }

        // 1. Battery SRAM save (`save.srm`)
        var srmDownloaded = false
        if (options.syncBatterySaves) {
            val localSrm = saveFileManager.saveRamFile(rom)
            val remoteSrm = gameChildren[SAVE_RAM_REMOTE_NAME]?.takeIf { !it.isFolder }
            val relPath = "games/$gameKey/$SAVE_RAM_REMOTE_NAME"
            val outcome = syncSingleFile(
                rom = rom,
                localFile = localSrm,
                remoteFile = remoteSrm,
                parentFolderId = gameFolder.id,
                remoteName = SAVE_RAM_REMOTE_NAME,
                mimeType = "application/octet-stream",
                relativePath = relPath,
                baseline = baseline,
                conflictPolicy = options.conflictPolicy,
            )
            recordOutcome(outcome)
            srmDownloaded = outcome == FileSyncOutcome.DOWNLOADED || outcome == FileSyncOutcome.DOWNLOADED_CONFLICT
        }

        // Invalidate stale local auto-states right after downloading a newer cloud `save.srm` and
        // BEFORE syncing states, so losing local auto-states are never uploaded to Drive. Any newer
        // remote auto-state (changed MD5 vs baseline, or no baseline) will still download in step 2;
        // any unchanged remote auto-state becomes LOCAL_DELETED and drops from states_index.json.
        if (srmDownloaded && !isGameRunning(rom)) {
            val localAutoStates = saveFileManager.listStates(rom).filter { it.isAuto }
            if (!isGameRunning(rom)) {
                saveFileManager.deleteAutoStates(rom)
                for (auto in localAutoStates) {
                    saveFileManager.deleteState(rom, auto.id, force = true)
                }
            }
        }

        // 2. Save states (`states_index.json` + `states/<id>.svs` + `states/<id>.png`)
        if (options.syncSaveStates) {
            val localInfos = saveFileManager.listStates(rom)
            val localRemoteEntries = localInfos.map { it.toRemoteEntry() }
            val remoteIndexFile = gameChildren[STATES_INDEX_REMOTE_NAME]?.takeIf { !it.isFolder }
            val remoteIndexRaw: ByteArray? = remoteIndexFile?.let { driveApi.downloadFile(it.id) }
            val decodedRemoteEntries: List<RemoteSaveStateEntry>? = remoteIndexRaw?.let { bytes ->
                parseRemoteStatesIndex(bytes)
            }
            val remoteStatesFolderExisting = gameChildren[STATES_FOLDER_NAME]?.takeIf { it.isFolder }

            val remoteStateChildrenPreFetched: Map<String, DriveFile>? =
                if (remoteIndexFile == null && remoteStatesFolderExisting != null) {
                    indexByNameLatest(driveApi.listChildren(remoteStatesFolderExisting.id).filter { !it.isFolder })
                } else {
                    null
                }

            val remoteEntries: List<RemoteSaveStateEntry> = decodedRemoteEntries ?: run {
                // Parity with iOS: rebuild missing states_index.json entries from existing remote `states/*.svs`.
                remoteStateChildrenPreFetched?.values.orEmpty()
                    .filter { it.name.endsWith(".svs", ignoreCase = true) }
                    .mapNotNull { file ->
                        val id = file.name.dropLast(4)
                        if (!isSafeStateId(id)) return@mapNotNull null
                        val kind = when {
                            id == SaveFileManager.QUICK_ID -> SaveStateInfo.KIND_QUICK
                            id == "auto" || id.startsWith("auto") -> SaveStateInfo.KIND_AUTO
                            else -> SaveStateInfo.KIND_GENERAL
                        }
                        RemoteSaveStateEntry(
                            id = id,
                            kind = kind,
                            name = null,
                            locked = false,
                            createdAtMs = file.modifiedTimeMs,
                            modifiedAtMs = file.modifiedTimeMs,
                            coreId = rom.system.coreId,
                            systemId = rom.systemId,
                        )
                    }
            }

            val normalizedRemoteEntries = normalizeStateEntries(remoteEntries)
            val merged = mergeStateIndices(localRemoteEntries, remoteEntries, options.conflictPolicy)
            val locallyDeletedStateIds = HashSet<String>()

            if (merged.isNotEmpty() || remoteStatesFolderExisting != null) {
                val remoteStatesFolder = remoteStatesFolderExisting
                    ?: driveApi.findOrCreateFolder(STATES_FOLDER_NAME, parentId = gameFolder.id)
                val remoteStateChildren = remoteStateChildrenPreFetched
                    ?: indexByNameLatest(driveApi.listChildren(remoteStatesFolder.id).filter { !it.isFolder })

                for (entry in merged) {
                    if (!isSafeStateId(entry.id)) continue

                    // State payload: local `<id>.state` <-> remote `states/<id>.svs`
                    val localStateFile = saveFileManager.stateFile(rom, entry.id)
                    val remoteStateName = "${entry.id}.svs"
                    val remoteStateFile = remoteStateChildren[remoteStateName]
                    val stateRelPath = "games/$gameKey/$STATES_FOLDER_NAME/$remoteStateName"
                    val stateOutcome = syncSingleFile(
                        rom = rom,
                        localFile = localStateFile,
                        remoteFile = remoteStateFile,
                        parentFolderId = remoteStatesFolder.id,
                        remoteName = remoteStateName,
                        mimeType = "application/octet-stream",
                        relativePath = stateRelPath,
                        baseline = baseline,
                        conflictPolicy = options.conflictPolicy,
                    )
                    recordOutcome(stateOutcome)
                    if (stateOutcome == FileSyncOutcome.LOCAL_DELETED) {
                        locallyDeletedStateIds.add(entry.id)
                        baseline.remove("games/$gameKey/$STATES_FOLDER_NAME/${entry.id}.png")
                        continue
                    }

                    // Optional screenshot thumbnail: local `<id>.png` <-> remote `states/<id>.png`
                    val localPngFile = saveFileManager.screenshotFile(rom, entry.id)
                    val remotePngName = "${entry.id}.png"
                    val remotePngFile = remoteStateChildren[remotePngName]
                    if (localPngFile.isFile || remotePngFile != null) {
                        val pngRelPath = "games/$gameKey/$STATES_FOLDER_NAME/$remotePngName"
                        val pngOutcome = syncSingleFile(
                            rom = rom,
                            localFile = localPngFile,
                            remoteFile = remotePngFile,
                            parentFolderId = remoteStatesFolder.id,
                            remoteName = remotePngName,
                            mimeType = "image/png",
                            relativePath = pngRelPath,
                            baseline = baseline,
                            conflictPolicy = options.conflictPolicy,
                        )
                        recordOutcome(pngOutcome)
                    }
                }

                val finalMerged = if (locallyDeletedStateIds.isEmpty()) {
                    merged
                } else {
                    merged.filter { it.id !in locallyDeletedStateIds }
                }

                // Update local `states.json` only if game is not running (re-checked right before write).
                if (!isGameRunning(rom)) {
                    val syncedLocalInfos = finalMerged
                        .filter { isSafeStateId(it.id) && saveFileManager.stateFile(rom, it.id).isFile }
                        .map { it.toLocalInfo() }
                    val existingLocalIndex = saveFileManager.readStateIndex(rom)
                    if (syncedLocalInfos != existingLocalIndex && !isGameRunning(rom)) {
                        saveFileManager.replaceStateIndex(rom, syncedLocalInfos)
                    }
                }

                // Upload merged `states_index.json` only when missing remotely or semantically changed
                // (avoids cross-platform ping-pong with iOS's compact sortedKeys JSON formatting).
                if (finalMerged.isNotEmpty() || remoteIndexFile != null) {
                    val semanticallyChanged = remoteIndexFile == null || finalMerged != normalizedRemoteEntries
                    if (semanticallyChanged) {
                        val mergedBytes = syncPrettyJson.encodeToString(finalMerged).toByteArray(Charsets.UTF_8)
                        driveApi.uploadFile(
                            parentId = gameFolder.id,
                            name = STATES_INDEX_REMOTE_NAME,
                            mimeType = "application/json",
                            content = mergedBytes,
                            existingFileId = remoteIndexFile?.id,
                            modifiedTimeMs = clock.nowMs(),
                        )
                    }
                }
            }
        }

        // 3. Game metadata (`meta.json`) — create if missing or update when anything was uploaded.
        val existingMeta = gameChildren[META_REMOTE_NAME]?.takeIf { !it.isFolder }
        val hasAnySyncedArtifact = saveFileManager.hasSaveRam(rom) ||
            saveFileManager.listStates(rom).isNotEmpty() ||
            gameChildren.containsKey(SAVE_RAM_REMOTE_NAME) ||
            gameChildren.containsKey(STATES_INDEX_REMOTE_NAME)
        if (hasAnySyncedArtifact && (existingMeta == null || anyUploadedForGame)) {
            val crcHex = rom.crc32?.takeIf { it != 0L }?.let { "%08x".format(Locale.US, it and 0xFFFFFFFFL) } ?: ""
            val meta = RemoteGameMeta(
                schemaVersion = 1,
                gameKey = gameKey,
                gameName = rom.title,
                systemId = rom.systemId,
                crc32 = crcHex,
                updatedAtMs = clock.nowMs(),
                updatedByDevice = options.deviceName,
            )
            val metaBytes = syncPrettyJson.encodeToString(meta).toByteArray(Charsets.UTF_8)
            driveApi.uploadFile(
                parentId = gameFolder.id,
                name = META_REMOTE_NAME,
                mimeType = "application/json",
                content = metaBytes,
                existingFileId = existingMeta?.id,
                modifiedTimeMs = meta.updatedAtMs,
            )
        }

        return GameSyncStats(
            uploaded = uploaded,
            downloaded = downloaded,
            skipped = skipped,
            conflicts = conflicts,
            skippedRunningGames = if (skippedRunning) 1 else 0,
        )
    }

    private suspend fun syncSingleFile(
        rom: RomEntry,
        localFile: File,
        remoteFile: DriveFile?,
        parentFolderId: String,
        remoteName: String,
        mimeType: String,
        relativePath: String,
        baseline: MutableMap<String, SyncBaselineEntry>,
        conflictPolicy: ConflictPolicy,
    ): FileSyncOutcome {
        val localExists = localFile.isFile
        val remoteExists = remoteFile != null

        if (!localExists && !remoteExists) {
            baseline.remove(relativePath)
            return FileSyncOutcome.NONE
        }

        val now = clock.nowMs()

        // Case A: Local only -> upload
        if (localExists && !remoteExists) {
            val localBytes = localFile.readBytes()
            val localMd5 = md5Hex(localBytes)
            val localMtime = localFile.lastModified()
            val uploaded = driveApi.uploadFile(
                parentId = parentFolderId,
                name = remoteName,
                mimeType = mimeType,
                content = localBytes,
                existingFileId = null,
                modifiedTimeMs = localMtime.takeIf { it > 0L },
            )
            baseline[relativePath] = SyncBaselineEntry(
                driveFileId = uploaded.id,
                localMd5 = localMd5,
                remoteMd5 = uploaded.md5Checksum ?: localMd5,
                localMtimeMs = localMtime,
                remoteModifiedTimeMs = uploaded.modifiedTimeMs,
                lastSyncedAtMs = now,
            )
            return FileSyncOutcome.UPLOADED
        }

        // Case B: Remote only -> check for local deletion vs download (unless game is running)
        if (!localExists && remoteFile != null) {
            val prev = baseline[relativePath]
            if (prev != null && remoteFile.md5Checksum != null &&
                remoteFile.md5Checksum.equals(prev.remoteMd5, ignoreCase = true)
            ) {
                // Previously synced file was deleted locally and remote hasn't changed -> do not resurrect.
                baseline.remove(relativePath)
                return FileSyncOutcome.LOCAL_DELETED
            }
            if (isGameRunning(rom)) {
                return FileSyncOutcome.SKIPPED_RUNNING
            }
            val downloadedBytes = driveApi.downloadFile(remoteFile.id)
            if (isGameRunning(rom)) {
                return FileSyncOutcome.SKIPPED_RUNNING
            }
            SaveFileManager.atomicWrite(localFile, downloadedBytes)
            if (remoteFile.modifiedTimeMs > 0L) {
                localFile.setLastModified(remoteFile.modifiedTimeMs)
            }
            val downloadedMd5 = md5Hex(downloadedBytes)
            baseline[relativePath] = SyncBaselineEntry(
                driveFileId = remoteFile.id,
                localMd5 = downloadedMd5,
                remoteMd5 = remoteFile.md5Checksum ?: downloadedMd5,
                localMtimeMs = localFile.lastModified(),
                remoteModifiedTimeMs = remoteFile.modifiedTimeMs,
                lastSyncedAtMs = now,
            )
            return FileSyncOutcome.DOWNLOADED
        }

        // Case C: Both exist
        val remote = remoteFile!!
        val localBytes = localFile.readBytes()
        val localMd5 = md5Hex(localBytes)
        val localMtime = localFile.lastModified()
        val remoteMd5 = remote.md5Checksum

        // Identical content check via MD5 (skips transfer even on first sync from a second device!)
        if (remoteMd5 != null && localMd5.equals(remoteMd5, ignoreCase = true)) {
            baseline[relativePath] = SyncBaselineEntry(
                driveFileId = remote.id,
                localMd5 = localMd5,
                remoteMd5 = remoteMd5,
                localMtimeMs = localMtime,
                remoteModifiedTimeMs = remote.modifiedTimeMs,
                lastSyncedAtMs = now,
            )
            return FileSyncOutcome.SKIPPED
        }

        val prev = baseline[relativePath]
        val localChanged = prev == null || !localMd5.equals(prev.localMd5, ignoreCase = true)
        val remoteChanged = prev == null ||
            (remoteMd5 != null && !remoteMd5.equals(prev.remoteMd5, ignoreCase = true)) ||
            (remoteMd5 == null && remote.modifiedTimeMs != prev.remoteModifiedTimeMs)

        // Fast-forward: only local changed -> upload
        if (localChanged && !remoteChanged) {
            val uploaded = driveApi.uploadFile(
                parentId = parentFolderId,
                name = remoteName,
                mimeType = mimeType,
                content = localBytes,
                existingFileId = remote.id,
                modifiedTimeMs = localMtime.takeIf { it > 0L },
            )
            baseline[relativePath] = SyncBaselineEntry(
                driveFileId = uploaded.id,
                localMd5 = localMd5,
                remoteMd5 = uploaded.md5Checksum ?: localMd5,
                localMtimeMs = localMtime,
                remoteModifiedTimeMs = uploaded.modifiedTimeMs,
                lastSyncedAtMs = now,
            )
            return FileSyncOutcome.UPLOADED
        }

        // Fast-forward: only remote changed -> download
        if (!localChanged && remoteChanged) {
            if (isGameRunning(rom)) {
                return FileSyncOutcome.SKIPPED_RUNNING
            }
            val downloadedBytes = driveApi.downloadFile(remote.id)
            if (isGameRunning(rom)) {
                return FileSyncOutcome.SKIPPED_RUNNING
            }
            SaveFileManager.atomicWrite(localFile, downloadedBytes)
            if (remote.modifiedTimeMs > 0L) {
                localFile.setLastModified(remote.modifiedTimeMs)
            }
            val downloadedMd5 = md5Hex(downloadedBytes)
            baseline[relativePath] = SyncBaselineEntry(
                driveFileId = remote.id,
                localMd5 = downloadedMd5,
                remoteMd5 = remote.md5Checksum ?: downloadedMd5,
                localMtimeMs = localFile.lastModified(),
                remoteModifiedTimeMs = remote.modifiedTimeMs,
                lastSyncedAtMs = now,
            )
            return FileSyncOutcome.DOWNLOADED
        }

        // True 3-way conflict: both local and remote changed (or no baseline & content differs)
        val uploadWins = when (conflictPolicy) {
            ConflictPolicy.PREFER_LOCAL -> true
            ConflictPolicy.PREFER_CLOUD -> false
            ConflictPolicy.NEWEST_WINS -> localMtime >= remote.modifiedTimeMs
        }

        if (uploadWins) {
            val uploaded = driveApi.uploadFile(
                parentId = parentFolderId,
                name = remoteName,
                mimeType = mimeType,
                content = localBytes,
                existingFileId = remote.id,
                modifiedTimeMs = localMtime.takeIf { it > 0L },
            )
            baseline[relativePath] = SyncBaselineEntry(
                driveFileId = uploaded.id,
                localMd5 = localMd5,
                remoteMd5 = uploaded.md5Checksum ?: localMd5,
                localMtimeMs = localMtime,
                remoteModifiedTimeMs = uploaded.modifiedTimeMs,
                lastSyncedAtMs = now,
            )
            return FileSyncOutcome.UPLOADED_CONFLICT
        } else {
            if (isGameRunning(rom)) {
                return FileSyncOutcome.SKIPPED_RUNNING_CONFLICT
            }
            val downloadedBytes = driveApi.downloadFile(remote.id)
            if (isGameRunning(rom)) {
                return FileSyncOutcome.SKIPPED_RUNNING_CONFLICT
            }
            // Back up local file before overwriting: `<file>.conflict-<ts>.bak` (with collision uniquifier)
            val backupFile = uniqueConflictBackupFile(localFile, now)
            SaveFileManager.atomicWrite(backupFile, localBytes)

            SaveFileManager.atomicWrite(localFile, downloadedBytes)
            if (remote.modifiedTimeMs > 0L) {
                localFile.setLastModified(remote.modifiedTimeMs)
            }
            val downloadedMd5 = md5Hex(downloadedBytes)
            baseline[relativePath] = SyncBaselineEntry(
                driveFileId = remote.id,
                localMd5 = downloadedMd5,
                remoteMd5 = remote.md5Checksum ?: downloadedMd5,
                localMtimeMs = localFile.lastModified(),
                remoteModifiedTimeMs = remote.modifiedTimeMs,
                lastSyncedAtMs = now,
            )
            return FileSyncOutcome.DOWNLOADED_CONFLICT
        }
    }

    private fun uniqueConflictBackupFile(localFile: File, timestampMs: Long): File {
        val parent = localFile.parentFile
        var candidate = File(parent, "${localFile.name}.conflict-$timestampMs.bak")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(parent, "${localFile.name}.conflict-$timestampMs-$suffix.bak")
            suffix++
        }
        return candidate
    }

    internal fun loadBaselineState(): SyncBaselineState {
        if (!baselineFile.isFile) return SyncBaselineState()
        val raw = runCatching { baselineFile.readText() }.getOrNull() ?: return SyncBaselineState()
        // Support both structured SyncBaselineState and legacy flat Map<String, SyncBaselineEntry>.
        runCatching {
            val root = syncJson.parseToJsonElement(raw)
            if (root is kotlinx.serialization.json.JsonObject && ("files" in root || "rootFolderId" in root)) {
                return syncJson.decodeFromJsonElement(SyncBaselineState.serializer(), root)
            }
        }
        val legacy = runCatching {
            syncJson.decodeFromString<Map<String, SyncBaselineEntry>>(raw)
        }.getOrDefault(emptyMap())
        return SyncBaselineState(rootFolderId = null, files = legacy)
    }

    internal fun loadBaseline(): Map<String, SyncBaselineEntry> = loadBaselineState().files

    internal fun saveBaselineState(state: SyncBaselineState) {
        runCatching {
            val bytes = syncPrettyJson.encodeToString(state).toByteArray(Charsets.UTF_8)
            SaveFileManager.atomicWrite(baselineFile, bytes)
        }
    }

    internal fun saveBaseline(map: Map<String, SyncBaselineEntry>) {
        saveBaselineState(SyncBaselineState(rootFolderId = null, files = map))
    }

    companion object {
        /**
         * Parses remote `states_index.json` element-by-element so a single malformed entry is skipped
         * rather than wiping the entire index. Throws [IOException] if the payload itself is not a
         * valid JSON array so the caller records a per-game error and never overwrites remote state.
         */
        internal fun parseRemoteStatesIndex(raw: ByteArray): List<RemoteSaveStateEntry> {
            val text = raw.toString(Charsets.UTF_8)
            val root = try {
                syncJson.parseToJsonElement(text)
            } catch (e: Exception) {
                throw IOException("Malformed remote states_index.json", e)
            }
            val array = root as? JsonArray
                ?: throw IOException("Remote states_index.json is not a JSON array")
            val entries = ArrayList<RemoteSaveStateEntry>(array.size)
            for (el in array) {
                val decoded = runCatching {
                    syncJson.decodeFromJsonElement(RemoteSaveStateEntry.serializer(), el)
                }.getOrNull() ?: continue
                if (isSafeStateId(decoded.id)) {
                    entries.add(decoded)
                }
            }
            return entries
        }

        internal fun normalizeStateEntries(entries: List<RemoteSaveStateEntry>): List<RemoteSaveStateEntry> =
            entries.filter { isSafeStateId(it.id) }
                .associateBy { it.id }
                .values
                .sortedWith(compareByDescending<RemoteSaveStateEntry> { it.modifiedAtMs }.thenBy { it.id })

        internal fun indexByNameLatest(files: List<DriveFile>): Map<String, DriveFile> {
            val map = LinkedHashMap<String, DriveFile>(files.size)
            for (file in files) {
                val prev = map[file.name]
                if (prev == null || file.modifiedTimeMs > prev.modifiedTimeMs ||
                    (file.modifiedTimeMs == prev.modifiedTimeMs && file.id > prev.id)
                ) {
                    map[file.name] = file
                }
            }
            return map
        }

        /**
         * Merges local and remote `states_index.json` entries by `id`.
         * Newer `modifiedAtMs` wins; equal timestamps tie-break using [conflictPolicy].
         */
        fun mergeStateIndices(
            local: List<RemoteSaveStateEntry>,
            remote: List<RemoteSaveStateEntry>,
            conflictPolicy: ConflictPolicy = ConflictPolicy.NEWEST_WINS,
        ): List<RemoteSaveStateEntry> {
            val localById = local.filter { isSafeStateId(it.id) }.associateBy { it.id }
            val remoteById = remote.filter { isSafeStateId(it.id) }.associateBy { it.id }
            val allIds = (localById.keys + remoteById.keys).distinct()
            val merged = allIds.map { id ->
                val l = localById[id]
                val r = remoteById[id]
                when {
                    l == null -> r!!
                    r == null -> l
                    r.modifiedAtMs > l.modifiedAtMs -> r
                    l.modifiedAtMs > r.modifiedAtMs -> l
                    conflictPolicy == ConflictPolicy.PREFER_CLOUD -> r
                    else -> l
                }
            }
            return merged.sortedWith(
                compareByDescending<RemoteSaveStateEntry> { it.modifiedAtMs }.thenBy { it.id },
            )
        }

        private fun SaveStateInfo.toRemoteEntry() = RemoteSaveStateEntry(
            id = id,
            kind = kind,
            name = name,
            locked = locked,
            createdAtMs = createdAt,
            modifiedAtMs = modifiedAt,
            coreId = coreId,
            systemId = systemId,
        )

        private fun RemoteSaveStateEntry.toLocalInfo() = SaveStateInfo(
            id = id,
            kind = kind,
            name = name,
            locked = locked,
            createdAt = createdAtMs,
            modifiedAt = modifiedAtMs,
            coreId = coreId,
            systemId = systemId,
        )
    }
}

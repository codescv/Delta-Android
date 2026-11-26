package com.codescv.delta.sync

import com.codescv.delta.i18n.DeltaI18n
import com.codescv.delta.i18n.StrKey
import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateInfo
import com.codescv.delta.saves.SaveTransferException
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
        DEFERRED_CONFLICT,
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
        val pendingConflicts = mutableListOf<SyncConflict>()
        val errors = mutableListOf<String>()

        try {
            onProgress(DeltaI18n.tr(StrKey.SYNC_PROGRESS_CONNECTING))
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

                onProgress(DeltaI18n.tr(StrKey.SYNC_PROGRESS_GAME, rom.title, idx + 1, total))
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
                    pendingConflicts += gameStats.pendingConflicts
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
            pendingConflicts = pendingConflicts,
            errors = errors,
            syncedAtMs = clock.nowMs(),
        )
    }

    /**
     * Resolves a single deferred [SyncConflict] produced under [ConflictPolicy.ASK].
     *
     * - [ConflictChoice.KEEP_LOCAL]: uploads local save (plus associated `.png`, `states_index.json`, and
     *   `meta.json`) and updates the sync baseline.
     * - [ConflictChoice.USE_CLOUD]: verifies the game is not running right before writing, backs up local
     *   bytes to `.conflict-<ts>[-<n>].bak`, atomically writes the cloud payload (plus `.png` and `states.json`
     *   for save states, or invalidates stale local auto-states for battery saves), and updates the baseline.
     */
    suspend fun resolveConflict(
        conflict: SyncConflict,
        choice: ConflictChoice,
        roms: List<RomEntry>,
        options: SyncOptions = SyncOptions(),
    ) {
        val rom = roms.firstOrNull { it.id == conflict.romId }
            ?: roms.firstOrNull { GameKey.compute(it) == conflict.gameKey }
            ?: throw IllegalStateException(DeltaI18n.tr(StrKey.SYNC_ERR_GAME_REMOVED, conflict.gameName))

        val initialState = loadBaselineState()
        val baseline = initialState.files.toMutableMap()
        var activeRootFolderId: String? = initialState.rootFolderId
        var baselineModified = false
        val now = clock.nowMs()

        suspend fun ensureGameFolderId(): String {
            conflict.remoteGameFolderId?.takeIf { it.isNotBlank() }?.let { return it }
            if (conflict.kind == SyncConflictKind.BATTERY_SAVE) {
                conflict.remoteParentFolderId?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val rootFolder = driveApi.findOrCreateFolder(DELTA_SYNC_ROOT_FOLDER, parentId = null)
            activeRootFolderId = rootFolder.id
            val gamesFolder = driveApi.findOrCreateFolder(GAMES_FOLDER_NAME, parentId = rootFolder.id)
            return driveApi.findOrCreateFolder(conflict.gameKey, parentId = gamesFolder.id).id
        }

        suspend fun ensureStatesFolderId(gameFolderId: String): String {
            if (conflict.kind == SyncConflictKind.SAVE_STATE) {
                conflict.remoteParentFolderId?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return driveApi.findOrCreateFolder(STATES_FOLDER_NAME, parentId = gameFolderId).id
        }

        try {
            when (choice) {
                ConflictChoice.KEEP_LOCAL -> {
                    val gameFolderId = ensureGameFolderId()
                    when (conflict.kind) {
                        SyncConflictKind.BATTERY_SAVE -> {
                            val localSrm = saveFileManager.saveRamFile(rom)
                            if (!localSrm.isFile) {
                                throw IOException(DeltaI18n.tr(StrKey.SYNC_ERR_LOCAL_SAVE_MISSING))
                            }
                            // Verify remote save.srm has not changed since conflict detection before overwriting.
                            val gameChildren = indexByNameLatest(driveApi.listChildren(gameFolderId))
                            val currentRemoteSrm = gameChildren[SAVE_RAM_REMOTE_NAME]?.takeIf { !it.isFolder }
                            verifyRemoteUnchanged(conflict, currentRemoteSrm)

                            val localBytes = localSrm.readBytes()
                            val localMd5 = md5Hex(localBytes)
                            val localMtime = localSrm.lastModified()
                            val uploaded = uploadFileWithFallback(
                                parentId = gameFolderId,
                                name = SAVE_RAM_REMOTE_NAME,
                                mimeType = "application/octet-stream",
                                content = localBytes,
                                existingFileId = conflict.remoteFileId,
                                modifiedTimeMs = localMtime.takeIf { it > 0L },
                            )
                            baseline[conflict.id] = SyncBaselineEntry(
                                driveFileId = uploaded.id,
                                localMd5 = localMd5,
                                remoteMd5 = uploaded.md5Checksum ?: localMd5,
                                localMtimeMs = localMtime,
                                remoteModifiedTimeMs = uploaded.modifiedTimeMs,
                                lastSyncedAtMs = now,
                            )
                            baselineModified = true
                            val existingMeta = gameChildren[META_REMOTE_NAME]?.takeIf { !it.isFolder }
                            uploadGameMeta(
                                gameFolderId = gameFolderId,
                                existingMetaId = existingMeta?.id,
                                rom = rom,
                                gameKey = conflict.gameKey,
                                deviceName = options.deviceName,
                                nowMs = now,
                            )
                        }

                        SyncConflictKind.SAVE_STATE -> {
                            val stateId = conflict.stateId?.takeIf { isSafeStateId(it) }
                                ?: throw IllegalArgumentException(DeltaI18n.tr(StrKey.SYNC_ERR_INVALID_STATE_ID))
                            val localStateFile = saveFileManager.stateFile(rom, stateId)
                            if (!localStateFile.isFile) {
                                throw IOException(DeltaI18n.tr(StrKey.SYNC_ERR_LOCAL_STATE_MISSING))
                            }
                            val statesFolderId = ensureStatesFolderId(gameFolderId)
                            // Verify remote <stateId>.svs has not changed since conflict detection before overwriting.
                            val stateChildren = indexByNameLatest(
                                driveApi.listChildren(statesFolderId).filter { !it.isFolder },
                            )
                            val currentRemoteState = stateChildren["$stateId.svs"]
                            verifyRemoteUnchanged(conflict, currentRemoteState)

                            val localBytes = localStateFile.readBytes()
                            val localMd5 = md5Hex(localBytes)
                            val localMtime = localStateFile.lastModified()
                            val uploaded = uploadFileWithFallback(
                                parentId = statesFolderId,
                                name = "$stateId.svs",
                                mimeType = "application/octet-stream",
                                content = localBytes,
                                existingFileId = conflict.remoteFileId,
                                modifiedTimeMs = localMtime.takeIf { it > 0L },
                            )
                            baseline[conflict.id] = SyncBaselineEntry(
                                driveFileId = uploaded.id,
                                localMd5 = localMd5,
                                remoteMd5 = uploaded.md5Checksum ?: localMd5,
                                localMtimeMs = localMtime,
                                remoteModifiedTimeMs = uploaded.modifiedTimeMs,
                                lastSyncedAtMs = now,
                            )
                            baselineModified = true

                            // Handle associated `.png` thumbnail alongside `.svs`.
                            val localPngFile = saveFileManager.screenshotFile(rom, stateId)
                            val remotePngName = "$stateId.png"
                            val remotePngFile = stateChildren[remotePngName]
                            val pngRelPath = "games/${conflict.gameKey}/$STATES_FOLDER_NAME/$remotePngName"
                            if (localPngFile.isFile) {
                                val pngBytes = localPngFile.readBytes()
                                val pngMd5 = md5Hex(pngBytes)
                                val pngMtime = localPngFile.lastModified()
                                if (remotePngFile != null && remotePngFile.md5Checksum != null &&
                                    pngMd5.equals(remotePngFile.md5Checksum, ignoreCase = true)
                                ) {
                                    baseline[pngRelPath] = SyncBaselineEntry(
                                        driveFileId = remotePngFile.id,
                                        localMd5 = pngMd5,
                                        remoteMd5 = remotePngFile.md5Checksum,
                                        localMtimeMs = pngMtime,
                                        remoteModifiedTimeMs = remotePngFile.modifiedTimeMs,
                                        lastSyncedAtMs = now,
                                    )
                                } else {
                                    val uploadedPng = uploadFileWithFallback(
                                        parentId = statesFolderId,
                                        name = remotePngName,
                                        mimeType = "image/png",
                                        content = pngBytes,
                                        existingFileId = remotePngFile?.id,
                                        modifiedTimeMs = pngMtime.takeIf { it > 0L },
                                    )
                                    baseline[pngRelPath] = SyncBaselineEntry(
                                        driveFileId = uploadedPng.id,
                                        localMd5 = pngMd5,
                                        remoteMd5 = uploadedPng.md5Checksum ?: pngMd5,
                                        localMtimeMs = pngMtime,
                                        remoteModifiedTimeMs = uploadedPng.modifiedTimeMs,
                                        lastSyncedAtMs = now,
                                    )
                                }
                            } else if (remotePngFile?.md5Checksum != null) {
                                // Local state has no thumbnail; record baseline so next sync won't resurrect cloud's thumbnail.
                                baseline[pngRelPath] = SyncBaselineEntry(
                                    driveFileId = remotePngFile.id,
                                    localMd5 = "",
                                    remoteMd5 = remotePngFile.md5Checksum,
                                    localMtimeMs = 0L,
                                    remoteModifiedTimeMs = remotePngFile.modifiedTimeMs,
                                    lastSyncedAtMs = now,
                                )
                            } else {
                                baseline.remove(pngRelPath)
                            }

                            // Update remote `states_index.json` and `meta.json`.
                            val gameChildren = indexByNameLatest(driveApi.listChildren(gameFolderId))
                            val remoteIndexFile = gameChildren[STATES_INDEX_REMOTE_NAME]?.takeIf { !it.isFolder }
                            val remoteEntries = remoteIndexFile?.let {
                                parseRemoteStatesIndex(driveApi.downloadFile(it.id))
                            }.orEmpty()
                            val localEntry = saveFileManager.state(rom, stateId)?.toRemoteEntry()
                                ?: RemoteSaveStateEntry(
                                    id = stateId,
                                    kind = inferStateKind(stateId),
                                    name = conflict.stateLabel,
                                    locked = false,
                                    createdAtMs = localMtime,
                                    modifiedAtMs = localMtime,
                                    coreId = rom.system.coreId,
                                    systemId = rom.systemId,
                                )
                            val updatedRemoteIndex = normalizeStateEntries(
                                remoteEntries.filter { it.id != stateId } + localEntry,
                            )
                            val indexBytes = syncPrettyJson.encodeToString(updatedRemoteIndex)
                                .toByteArray(Charsets.UTF_8)
                            driveApi.uploadFile(
                                parentId = gameFolderId,
                                name = STATES_INDEX_REMOTE_NAME,
                                mimeType = "application/json",
                                content = indexBytes,
                                existingFileId = remoteIndexFile?.id,
                                modifiedTimeMs = now,
                            )

                            val existingMeta = gameChildren[META_REMOTE_NAME]?.takeIf { !it.isFolder }
                            uploadGameMeta(
                                gameFolderId = gameFolderId,
                                existingMetaId = existingMeta?.id,
                                rom = rom,
                                gameKey = conflict.gameKey,
                                deviceName = options.deviceName,
                                nowMs = now,
                            )
                        }
                    }
                }

                ConflictChoice.USE_CLOUD -> {
                    if (isGameRunning(rom)) {
                        throw SaveTransferException.gameRunning()
                    }
                    when (conflict.kind) {
                        SyncConflictKind.BATTERY_SAVE -> {
                            val downloadedBytes = driveApi.downloadFile(conflict.remoteFileId)
                            if (isGameRunning(rom)) {
                                throw SaveTransferException.gameRunning()
                            }
                            val localSrm = saveFileManager.saveRamFile(rom)
                            if (localSrm.isFile) {
                                val backupFile = uniqueConflictBackupFile(localSrm, now)
                                SaveFileManager.atomicWrite(backupFile, localSrm.readBytes())
                            }
                            SaveFileManager.atomicWrite(localSrm, downloadedBytes)
                            if (conflict.remoteMtimeMs > 0L) {
                                localSrm.setLastModified(conflict.remoteMtimeMs)
                            }
                            invalidateLocalAutoStates(rom)
                            val downloadedMd5 = md5Hex(downloadedBytes)
                            baseline[conflict.id] = SyncBaselineEntry(
                                driveFileId = conflict.remoteFileId,
                                localMd5 = downloadedMd5,
                                remoteMd5 = downloadedMd5,
                                localMtimeMs = localSrm.lastModified(),
                                remoteModifiedTimeMs = conflict.remoteMtimeMs,
                                lastSyncedAtMs = now,
                            )
                            baselineModified = true
                        }

                        SyncConflictKind.SAVE_STATE -> {
                            val stateId = conflict.stateId?.takeIf { isSafeStateId(it) }
                                ?: throw IllegalArgumentException(DeltaI18n.tr(StrKey.SYNC_ERR_INVALID_STATE_ID))
                            val downloadedBytes = driveApi.downloadFile(conflict.remoteFileId)

                            val gameFolderId = ensureGameFolderId()
                            val statesFolderId = ensureStatesFolderId(gameFolderId)
                            val stateChildren = indexByNameLatest(
                                driveApi.listChildren(statesFolderId).filter { !it.isFolder },
                            )
                            val remotePngName = "$stateId.png"
                            val remotePngFile = stateChildren[remotePngName]
                            val downloadedPngBytes = remotePngFile?.let { driveApi.downloadFile(it.id) }

                            val gameChildren = indexByNameLatest(driveApi.listChildren(gameFolderId))
                            val remoteIndexFile = gameChildren[STATES_INDEX_REMOTE_NAME]?.takeIf { !it.isFolder }
                            val remoteEntry = remoteIndexFile?.let { file ->
                                runCatching {
                                    parseRemoteStatesIndex(driveApi.downloadFile(file.id))
                                        .firstOrNull { it.id == stateId }
                                }.getOrNull()
                            }

                            // Re-check running game guard immediately before mutating local files (TOCTOU safe).
                            if (isGameRunning(rom)) {
                                throw SaveTransferException.gameRunning()
                            }

                            val localStateFile = saveFileManager.stateFile(rom, stateId)
                            if (localStateFile.isFile) {
                                val backupFile = uniqueConflictBackupFile(localStateFile, now)
                                SaveFileManager.atomicWrite(backupFile, localStateFile.readBytes())
                            }
                            SaveFileManager.atomicWrite(localStateFile, downloadedBytes)
                            if (conflict.remoteMtimeMs > 0L) {
                                localStateFile.setLastModified(conflict.remoteMtimeMs)
                            }
                            val downloadedMd5 = md5Hex(downloadedBytes)
                            baseline[conflict.id] = SyncBaselineEntry(
                                driveFileId = conflict.remoteFileId,
                                localMd5 = downloadedMd5,
                                remoteMd5 = downloadedMd5,
                                localMtimeMs = localStateFile.lastModified(),
                                remoteModifiedTimeMs = conflict.remoteMtimeMs,
                                lastSyncedAtMs = now,
                            )
                            baselineModified = true

                            // Apply or remove associated `.png` thumbnail alongside `.svs`.
                            val localPngFile = saveFileManager.screenshotFile(rom, stateId)
                            val pngRelPath = "games/${conflict.gameKey}/$STATES_FOLDER_NAME/$remotePngName"
                            if (remotePngFile != null && downloadedPngBytes != null) {
                                SaveFileManager.atomicWrite(localPngFile, downloadedPngBytes)
                                if (remotePngFile.modifiedTimeMs > 0L) {
                                    localPngFile.setLastModified(remotePngFile.modifiedTimeMs)
                                }
                                val pngMd5 = md5Hex(downloadedPngBytes)
                                baseline[pngRelPath] = SyncBaselineEntry(
                                    driveFileId = remotePngFile.id,
                                    localMd5 = pngMd5,
                                    remoteMd5 = pngMd5,
                                    localMtimeMs = localPngFile.lastModified(),
                                    remoteModifiedTimeMs = remotePngFile.modifiedTimeMs,
                                    lastSyncedAtMs = now,
                                )
                            } else {
                                if (localPngFile.isFile) {
                                    localPngFile.delete()
                                }
                                baseline.remove(pngRelPath)
                            }

                            // Update local `states.json` entry for `stateId`.
                            val existingLocalIndex = saveFileManager.readStateIndex(rom)
                            val existingLocalEntry = existingLocalIndex.firstOrNull { it.id == stateId }
                            val updatedLocalInfo = remoteEntry?.toLocalInfo()
                                ?: existingLocalEntry?.copy(
                                    modifiedAt = conflict.remoteMtimeMs.takeIf { it > 0L } ?: now,
                                )
                                ?: SaveStateInfo(
                                    id = stateId,
                                    kind = inferStateKind(stateId),
                                    name = conflict.stateLabel,
                                    locked = false,
                                    createdAt = conflict.remoteMtimeMs.takeIf { it > 0L } ?: now,
                                    modifiedAt = conflict.remoteMtimeMs.takeIf { it > 0L } ?: now,
                                    coreId = rom.system.coreId,
                                    systemId = rom.systemId,
                                )
                            val updatedIndex = existingLocalIndex.filter { it.id != stateId } + updatedLocalInfo
                            if (!isGameRunning(rom)) {
                                saveFileManager.replaceStateIndex(rom, updatedIndex)
                            }
                        }
                    }
                }
            }
        } finally {
            if (baselineModified && shouldPersistBaseline()) {
                saveBaselineState(SyncBaselineState(rootFolderId = activeRootFolderId, files = baseline))
            }
        }
    }

    private fun verifyRemoteUnchanged(conflict: SyncConflict, currentRemote: DriveFile?) {
        if (currentRemote == null || currentRemote.id != conflict.remoteFileId) {
            throw StaleConflictException()
        }
        val expectedMd5 = conflict.remoteMd5
        if (expectedMd5 != null) {
            if (currentRemote.md5Checksum == null ||
                !currentRemote.md5Checksum.equals(expectedMd5, ignoreCase = true)
            ) {
                throw StaleConflictException()
            }
        } else if (currentRemote.modifiedTimeMs != conflict.remoteMtimeMs) {
            throw StaleConflictException()
        }
    }

    private suspend fun uploadFileWithFallback(
        parentId: String,
        name: String,
        mimeType: String,
        content: ByteArray,
        existingFileId: String?,
        modifiedTimeMs: Long?,
    ): DriveFile {
        return try {
            driveApi.uploadFile(
                parentId = parentId,
                name = name,
                mimeType = mimeType,
                content = content,
                existingFileId = existingFileId,
                modifiedTimeMs = modifiedTimeMs,
            )
        } catch (notFound: DriveFileNotFoundException) {
            if (existingFileId == null) throw notFound
            driveApi.uploadFile(
                parentId = parentId,
                name = name,
                mimeType = mimeType,
                content = content,
                existingFileId = null,
                modifiedTimeMs = modifiedTimeMs,
            )
        }
    }

    private suspend fun uploadGameMeta(
        gameFolderId: String,
        existingMetaId: String?,
        rom: RomEntry,
        gameKey: String,
        deviceName: String,
        nowMs: Long,
    ) {
        val crcHex = rom.crc32?.takeIf { it != 0L }?.let { "%08x".format(Locale.US, it and 0xFFFFFFFFL) } ?: ""
        val meta = RemoteGameMeta(
            schemaVersion = 1,
            gameKey = gameKey,
            gameName = rom.title,
            systemId = rom.systemId,
            crc32 = crcHex,
            updatedAtMs = nowMs,
            updatedByDevice = deviceName,
        )
        val metaBytes = syncPrettyJson.encodeToString(meta).toByteArray(Charsets.UTF_8)
        driveApi.uploadFile(
            parentId = gameFolderId,
            name = META_REMOTE_NAME,
            mimeType = "application/json",
            content = metaBytes,
            existingFileId = existingMetaId,
            modifiedTimeMs = meta.updatedAtMs,
        )
    }

    private fun invalidateLocalAutoStates(rom: RomEntry) {
        val localAutoStates = saveFileManager.listStates(rom).filter { it.isAuto }
        if (!isGameRunning(rom)) {
            saveFileManager.deleteAutoStates(rom)
            for (auto in localAutoStates) {
                saveFileManager.deleteState(rom, auto.id, force = true)
            }
        }
    }

    private data class GameSyncStats(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val skipped: Int = 0,
        val conflicts: Int = 0,
        val skippedRunningGames: Int = 0,
        val pendingConflicts: List<SyncConflict> = emptyList(),
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
        val pendingConflicts = mutableListOf<SyncConflict>()

        val gameFolder = existingRemoteGameFolder
            ?: driveApi.findOrCreateFolder(gameKey, parentId = gamesFolderId)
        val gameChildren = indexByNameLatest(driveApi.listChildren(gameFolder.id))

        var remoteDeviceNameLoaded = false
        var cachedRemoteDeviceName: String? = null
        suspend fun fetchRemoteDeviceName(): String? {
            if (remoteDeviceNameLoaded) return cachedRemoteDeviceName
            remoteDeviceNameLoaded = true
            val metaFile = gameChildren[META_REMOTE_NAME]?.takeIf { !it.isFolder } ?: return null
            cachedRemoteDeviceName = runCatching {
                val bytes = driveApi.downloadFile(metaFile.id)
                syncJson.decodeFromString<RemoteGameMeta>(bytes.toString(Charsets.UTF_8))
                    .updatedByDevice
                    .takeIf { it.isNotBlank() }
            }.getOrNull()
            return cachedRemoteDeviceName
        }

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
                FileSyncOutcome.DEFERRED_CONFLICT -> conflicts++
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
            if (outcome == FileSyncOutcome.DEFERRED_CONFLICT && remoteSrm != null) {
                pendingConflicts += SyncConflict(
                    id = relPath,
                    gameKey = gameKey,
                    gameName = rom.title,
                    systemId = rom.systemId,
                    romId = rom.id,
                    kind = SyncConflictKind.BATTERY_SAVE,
                    stateId = null,
                    stateLabel = null,
                    localMtimeMs = localSrm.lastModified(),
                    localSizeBytes = localSrm.length(),
                    remoteMtimeMs = remoteSrm.modifiedTimeMs,
                    remoteSizeBytes = remoteSrm.size,
                    remoteDeviceName = fetchRemoteDeviceName(),
                    remoteFileId = remoteSrm.id,
                    remoteMd5 = remoteSrm.md5Checksum,
                    remoteParentFolderId = gameFolder.id,
                    remoteGameFolderId = gameFolder.id,
                )
            }
            srmDownloaded = outcome == FileSyncOutcome.DOWNLOADED || outcome == FileSyncOutcome.DOWNLOADED_CONFLICT
        }

        // Invalidate stale local auto-states right after downloading a newer cloud `save.srm` and
        // BEFORE syncing states, so losing local auto-states are never uploaded to Drive. Any newer
        // remote auto-state (changed MD5 vs baseline, or no baseline) will still download in step 2;
        // any unchanged remote auto-state becomes LOCAL_DELETED and drops from states_index.json.
        if (srmDownloaded && !isGameRunning(rom)) {
            invalidateLocalAutoStates(rom)
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
                        RemoteSaveStateEntry(
                            id = id,
                            kind = inferStateKind(id),
                            name = null,
                            locked = false,
                            createdAtMs = file.modifiedTimeMs,
                            modifiedAtMs = file.modifiedTimeMs,
                            coreId = rom.system.coreId,
                            systemId = rom.systemId,
                        )
                    }
            }

            val localById = localRemoteEntries.associateBy { it.id }
            val remoteById = remoteEntries.associateBy { it.id }
            val normalizedRemoteEntries = normalizeStateEntries(remoteEntries)
            val merged = mergeStateIndices(localRemoteEntries, remoteEntries, options.conflictPolicy)
            val locallyDeletedStateIds = HashSet<String>()
            val conflictedStateIds = HashSet<String>()

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
                    if (stateOutcome == FileSyncOutcome.DEFERRED_CONFLICT && remoteStateFile != null) {
                        conflictedStateIds.add(entry.id)
                        pendingConflicts += SyncConflict(
                            id = stateRelPath,
                            gameKey = gameKey,
                            gameName = rom.title,
                            systemId = rom.systemId,
                            romId = rom.id,
                            kind = SyncConflictKind.SAVE_STATE,
                            stateId = entry.id,
                            stateLabel = formatStateLabel(localById[entry.id], remoteById[entry.id], entry.id),
                            localMtimeMs = localStateFile.lastModified(),
                            localSizeBytes = localStateFile.length(),
                            remoteMtimeMs = remoteStateFile.modifiedTimeMs,
                            remoteSizeBytes = remoteStateFile.size,
                            remoteDeviceName = fetchRemoteDeviceName(),
                            remoteFileId = remoteStateFile.id,
                            remoteMd5 = remoteStateFile.md5Checksum,
                            remoteParentFolderId = remoteStatesFolder.id,
                            remoteGameFolderId = gameFolder.id,
                        )
                        // Defer associated `.png` thumbnail alongside the conflicting `.svs`.
                        continue
                    }

                    // Optional screenshot thumbnail: local `<id>.png` <-> remote `states/<id>.png`
                    // Never prompt separately for `.png`; follow the winning side of its `.svs` (or newest).
                    val localPngFile = saveFileManager.screenshotFile(rom, entry.id)
                    val remotePngName = "${entry.id}.png"
                    val remotePngFile = remoteStateChildren[remotePngName]
                    if (localPngFile.isFile || remotePngFile != null) {
                        val pngRelPath = "games/$gameKey/$STATES_FOLDER_NAME/$remotePngName"
                        val pngPolicy = when {
                            stateOutcome == FileSyncOutcome.UPLOADED || stateOutcome == FileSyncOutcome.UPLOADED_CONFLICT ->
                                ConflictPolicy.PREFER_LOCAL
                            stateOutcome == FileSyncOutcome.DOWNLOADED || stateOutcome == FileSyncOutcome.DOWNLOADED_CONFLICT ->
                                ConflictPolicy.PREFER_CLOUD
                            options.conflictPolicy == ConflictPolicy.ASK ->
                                ConflictPolicy.NEWEST_WINS
                            else -> options.conflictPolicy
                        }
                        val pngOutcome = syncSingleFile(
                            rom = rom,
                            localFile = localPngFile,
                            remoteFile = remotePngFile,
                            parentFolderId = remoteStatesFolder.id,
                            remoteName = remotePngName,
                            mimeType = "image/png",
                            relativePath = pngRelPath,
                            baseline = baseline,
                            conflictPolicy = pngPolicy,
                        )
                        recordOutcome(pngOutcome)
                    }
                }

                val finalMerged = if (locallyDeletedStateIds.isEmpty()) {
                    merged
                } else {
                    merged.filter { it.id !in locallyDeletedStateIds }
                }

                // Preserve local metadata for deferred-conflict states until the user resolves them.
                val localIndexEntries = if (conflictedStateIds.isEmpty()) {
                    finalMerged
                } else {
                    normalizeStateEntries(
                        finalMerged.map { entry ->
                            if (entry.id in conflictedStateIds) localById[entry.id] ?: entry else entry
                        },
                    )
                }

                // Update local `states.json` only if game is not running (re-checked right before write).
                if (!isGameRunning(rom)) {
                    val syncedLocalInfos = localIndexEntries
                        .filter { isSafeStateId(it.id) && saveFileManager.stateFile(rom, it.id).isFile }
                        .map { it.toLocalInfo() }
                    val existingLocalIndex = saveFileManager.readStateIndex(rom)
                    if (syncedLocalInfos != existingLocalIndex && !isGameRunning(rom)) {
                        saveFileManager.replaceStateIndex(rom, syncedLocalInfos)
                    }
                }

                // Preserve remote metadata for deferred-conflict states until the user resolves them.
                val remoteIndexEntries = if (conflictedStateIds.isEmpty()) {
                    finalMerged
                } else {
                    normalizeStateEntries(
                        finalMerged.map { entry ->
                            if (entry.id in conflictedStateIds) remoteById[entry.id] ?: entry else entry
                        },
                    )
                }

                // Upload merged `states_index.json` only when missing remotely or semantically changed
                // (avoids cross-platform ping-pong with iOS's compact sortedKeys JSON formatting).
                if (remoteIndexEntries.isNotEmpty() || remoteIndexFile != null) {
                    val semanticallyChanged = remoteIndexFile == null || remoteIndexEntries != normalizedRemoteEntries
                    if (semanticallyChanged) {
                        val mergedBytes = syncPrettyJson.encodeToString(remoteIndexEntries).toByteArray(Charsets.UTF_8)
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
            uploadGameMeta(
                gameFolderId = gameFolder.id,
                existingMetaId = existingMeta?.id,
                rom = rom,
                gameKey = gameKey,
                deviceName = options.deviceName,
                nowMs = clock.nowMs(),
            )
        }

        return GameSyncStats(
            uploaded = uploaded,
            downloaded = downloaded,
            skipped = skipped,
            conflicts = conflicts,
            skippedRunningGames = if (skippedRunning) 1 else 0,
            pendingConflicts = pendingConflicts,
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
        if (conflictPolicy == ConflictPolicy.ASK) {
            return FileSyncOutcome.DEFERRED_CONFLICT
        }
        val uploadWins = when (conflictPolicy) {
            ConflictPolicy.PREFER_LOCAL -> true
            ConflictPolicy.PREFER_CLOUD -> false
            ConflictPolicy.NEWEST_WINS -> localMtime >= remote.modifiedTimeMs
            ConflictPolicy.ASK -> return FileSyncOutcome.DEFERRED_CONFLICT
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

        private fun inferStateKind(id: String): String = when {
            id == SaveFileManager.QUICK_ID -> SaveStateInfo.KIND_QUICK
            id == "auto" || id.startsWith("auto") -> SaveStateInfo.KIND_AUTO
            else -> SaveStateInfo.KIND_GENERAL
        }

        internal fun formatStateLabel(
            local: RemoteSaveStateEntry?,
            remote: RemoteSaveStateEntry?,
            id: String,
        ): String {
            val named = local?.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: remote?.name?.trim()?.takeIf { it.isNotEmpty() }
            if (named != null) return named
            val kind = local?.kind ?: remote?.kind ?: inferStateKind(id)
            return when {
                kind == SaveStateInfo.KIND_QUICK || id == SaveFileManager.QUICK_ID -> DeltaI18n.tr(StrKey.SAVES_QUICK_SAVE)
                id == "auto1" -> DeltaI18n.tr(StrKey.SYNC_STATE_AUTO_1)
                id == "auto2" -> DeltaI18n.tr(StrKey.SYNC_STATE_AUTO_2)
                kind == SaveStateInfo.KIND_AUTO || id.startsWith("auto") -> DeltaI18n.tr(StrKey.SAVES_AUTO_SAVE)
                else -> id
            }
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

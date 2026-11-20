package com.codescv.delta.sync

import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateContainer
import com.codescv.delta.saves.SaveStateHeader
import com.codescv.delta.saves.SaveStateType
import com.codescv.delta.settings.PreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Adversarial edge cases for the "Ask Every Time" conflict picker (written by android-conflict-tester).
 */
class GoogleDriveSyncConflictPickerAdversarialTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * Device A detects a conflict, user leaves the dialog open; meanwhile Device C uploads an even newer
     * save.srm to Drive. Tapping "Keep This Device" must NOT silently clobber Device C's save the user never saw.
     */
    @Test
    fun keepLocal_batterySave_cloudChangedAfterDetection_mustNotSilentlyOverwriteUnseenCloudVersion() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("keepLocalStaleRemote")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Zelda.gba", "gba", 0x0BADF00DL)
            saves.writeSaveRam("V0".toByteArray(), rom)
            val engine = makeEngine(server, saves, root)
            engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))

            saves.writeSaveRam("LOCAL-A".toByteArray(), rom)
            val remoteSrm = server.findByPath("Delta Sync", "games", "gba_0badf00d", "save.srm")!!
            remoteSrm.bytes = "CLOUD-B".toByteArray()
            remoteSrm.modifiedTimeMs = 1_700_000_050_000L
            val conflict = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
                .pendingConflicts.single()
            val baselineBefore = engine.loadBaseline()[conflict.id]

            // Third device updates cloud AFTER the conflict was detected.
            val deviceCBytes = "CLOUD-C-EVEN-NEWER".toByteArray()
            remoteSrm.bytes = deviceCBytes
            remoteSrm.modifiedTimeMs = 1_700_000_090_000L

            val outcome = runCatching {
                engine.resolveConflict(conflict, ConflictChoice.KEEP_LOCAL, listOf(rom))
            }
            assertTrue(
                "KEEP_LOCAL must fail (stale conflict) instead of overwriting an unseen newer cloud save",
                outcome.isFailure,
            )
            assertArrayEquals("Device C's cloud save must survive", deviceCBytes, remoteSrm.bytes)
            assertArrayEquals("LOCAL-A".toByteArray(), saves.readSaveRam(rom))
            assertEquals("Baseline must not be advanced", baselineBefore, engine.loadBaseline()[conflict.id])

            // A fresh sync re-detects the conflict against the new cloud version.
            val again = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(1, again.pendingConflicts.size)
            assertEquals(deviceCBytes.size.toLong(), again.pendingConflicts.single().remoteSizeBytes)
        }
    }

    @Test
    fun keepLocal_saveState_cloudChangedAfterDetection_mustNotSilentlyOverwriteUnseenCloudVersion() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("keepLocalStaleRemoteState")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Metroid.gba", "gba", 0x0DDBA11L)
            val header = SaveStateHeader("gba", "gba", 0x0DDBA11L, 1000.0)
            saves.writeState(rom, "L-S1".toByteArray(), header, null, SaveStateType.GENERAL, id = "s1", name = "S1")

            val r = server.seedFolder("Delta Sync")
            val g = server.seedFolder("games", r.id)
            val gameFolder = server.seedFolder("gba_00ddba11", g.id)
            val statesFolder = server.seedFolder("states", gameFolder.id)
            val remoteNode = server.seedFile(
                statesFolder.id, "s1.svs",
                SaveStateContainer.encode("R-S1".toByteArray(), header),
                modifiedTimeMs = 1_700_000_020_000L,
            )

            val engine = makeEngine(server, saves, root)
            val conflict = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
                .pendingConflicts.single()

            val deviceCBytes = SaveStateContainer.encode("R-S1-DEVICE-C".toByteArray(), header)
            remoteNode.bytes = deviceCBytes
            remoteNode.modifiedTimeMs = 1_700_000_090_000L

            val outcome = runCatching {
                engine.resolveConflict(conflict, ConflictChoice.KEEP_LOCAL, listOf(rom))
            }
            assertTrue("KEEP_LOCAL must fail on stale save-state conflict", outcome.isFailure)
            assertArrayEquals("Device C's cloud state must survive", deviceCBytes, remoteNode.bytes)
        }
    }

    /** User edits local save between detection and tapping "Use Google Drive": the .bak must hold the LATEST local bytes. */
    @Test
    fun useCloud_batterySave_localEditedAfterDetection_backupHoldsLatestLocalBytes() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("useCloudLocalEdited")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Kirby.gba", "gba", 0x13572468L)
            saves.writeSaveRam("V0".toByteArray(), rom)
            val engine = makeEngine(server, saves, root)
            engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))

            saves.writeSaveRam("LOCAL-1".toByteArray(), rom)
            val remoteSrm = server.findByPath("Delta Sync", "games", "gba_13572468", "save.srm")!!
            remoteSrm.bytes = "CLOUD-1".toByteArray()
            remoteSrm.modifiedTimeMs = 1_700_000_050_000L
            val conflict = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
                .pendingConflicts.single()

            val latestLocal = "LOCAL-2-PLAYED-MORE".toByteArray()
            saves.writeSaveRam(latestLocal, rom)

            engine.resolveConflict(conflict, ConflictChoice.USE_CLOUD, listOf(rom))
            assertArrayEquals("CLOUD-1".toByteArray(), saves.readSaveRam(rom))
            val backups = saves.saveRamFile(rom).parentFile!!
                .listFiles { f -> f.name.startsWith("Kirby.srm.conflict-") && f.name.endsWith(".bak") }!!
            assertEquals(1, backups.size)
            assertArrayEquals(latestLocal, backups[0].readBytes())

            val next = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(0, next.conflicts)
            assertEquals(0, next.uploaded)
            assertEquals(0, next.downloaded)
        }
    }

    /** A deferred conflict must leave the baseline entry for that path byte-for-byte untouched. */
    @Test
    fun askDeferral_doesNotTouchBaselineEntryForConflictedPath_andRepeatSyncIsStable() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("askBaselineStable")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "FFVI.sfc", "snes", 0x24681357L)
            saves.writeSaveRam("V0".toByteArray(), rom)
            val engine = makeEngine(server, saves, root)
            engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            val relPath = "games/snes_24681357/save.srm"
            val baselineBefore = engine.loadBaseline()[relPath]
            assertNotNull(baselineBefore)

            saves.writeSaveRam("LOCAL".toByteArray(), rom)
            val remoteSrm = server.findByPath("Delta Sync", "games", "snes_24681357", "save.srm")!!
            remoteSrm.bytes = "CLOUD".toByteArray()
            remoteSrm.modifiedTimeMs = 1_700_000_050_000L

            repeat(3) {
                val res = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
                assertEquals(1, res.pendingConflicts.size)
                assertEquals(0, res.uploaded)
                assertEquals(0, res.downloaded)
                assertEquals(baselineBefore, engine.loadBaseline()[relPath])
                assertArrayEquals("LOCAL".toByteArray(), saves.readSaveRam(rom))
                assertArrayEquals("CLOUD".toByteArray(), remoteSrm.bytes)
            }
        }
    }

    /** After "Later", a NEW sync that still has conflicts re-shows the prompt; resolving the last one clears all flags. */
    @Test
    fun syncManager_dismissThenNewSyncReshowsPrompt_andLastResolutionResetsFlags() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("mgrDismissReshow")
            val saves = SaveFileManager(root)
            val prefs = PreferencesStore(File(root, "preferences.json"))
            val rom = makeRom(root, "Pokemon.gba", "gba", 0x77778888L)
            saves.writeSaveRam("LOCAL".toByteArray(), rom)
            val r = server.seedFolder("Delta Sync")
            val g = server.seedFolder("games", r.id)
            val gameFolder = server.seedFolder("gba_77778888", g.id)
            server.seedFile(gameFolder.id, "save.srm", "CLOUD".toByteArray(), modifiedTimeMs = 1_700_000_050_000L)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val manager = GoogleDriveSyncManager(
                    secretStore = InMemorySyncSecretStore(
                        StoredOAuthCredentials(
                            clientId = "cid",
                            accessToken = server.validAccessToken,
                            refreshToken = "rt",
                            expiresAtMs = Long.MAX_VALUE / 2,
                            accountEmail = "p@example.com",
                        ),
                    ),
                    preferences = prefs,
                    romsProvider = { listOf(rom) },
                    saves = saves,
                    filesDir = root,
                    scope = scope,
                    apiBase = server.apiBase,
                    uploadBase = server.uploadBase,
                    tokenEndpoint = server.tokenEndpoint,
                    revokeEndpoint = server.revokeEndpoint,
                )
                manager.syncNow()
                manager.dismissConflictPrompt()
                assertTrue(manager.uiState.value.conflictPromptDismissed)

                manager.syncNow()
                assertEquals(1, manager.uiState.value.pendingConflicts.size)
                assertFalse("New sync with conflicts re-shows prompt", manager.uiState.value.conflictPromptDismissed)

                // showConflictPrompt with nothing pending is a no-op; resolution of the last conflict resets flags.
                manager.dismissConflictPrompt()
                val res = manager.resolveConflict(manager.uiState.value.pendingConflicts.single(), ConflictChoice.USE_CLOUD)
                assertTrue(res.isSuccess)
                assertTrue(manager.uiState.value.pendingConflicts.isEmpty())
                assertFalse(manager.uiState.value.conflictPromptDismissed)
                manager.showConflictPrompt()
                assertFalse(manager.uiState.value.conflictPromptDismissed)

                val after = manager.syncNow()
                assertEquals(0, after.conflicts)
                assertTrue(manager.uiState.value.pendingConflicts.isEmpty())
            } finally {
                scope.cancel()
            }
        }
    }

    /** Resolving the same conflict twice (double-tap reaching the manager) must not fail or corrupt anything. */
    @Test
    fun resolveConflict_useCloudTwice_isIdempotent() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("useCloudTwice")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Castlevania.gba", "gba", 0x31415926L)
            saves.writeSaveRam("V0".toByteArray(), rom)
            val engine = makeEngine(server, saves, root)
            engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            saves.writeSaveRam("LOCAL".toByteArray(), rom)
            val remoteSrm = server.findByPath("Delta Sync", "games", "gba_31415926", "save.srm")!!
            remoteSrm.bytes = "CLOUD".toByteArray()
            remoteSrm.modifiedTimeMs = 1_700_000_050_000L
            val conflict = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
                .pendingConflicts.single()

            engine.resolveConflict(conflict, ConflictChoice.USE_CLOUD, listOf(rom))
            try {
                engine.resolveConflict(conflict, ConflictChoice.USE_CLOUD, listOf(rom))
            } catch (e: Exception) {
                fail("Second identical USE_CLOUD should be harmless: $e")
            }
            assertArrayEquals("CLOUD".toByteArray(), saves.readSaveRam(rom))
            val next = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(0, next.conflicts)
        }
    }

    // --------------------------------------------------------------------------- helpers

    private fun makeRom(root: File, fileName: String, systemId: String, crc32: Long?): RomEntry {
        val romFile = File(File(root, "ROMs").apply { mkdirs() }, fileName)
        romFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        return RomEntry(path = romFile.absolutePath, systemId = systemId, fileSize = romFile.length(), crc32 = crc32)
    }

    private fun makeEngine(
        server: MockDriveHttpServer,
        saves: SaveFileManager,
        root: File,
        clockMs: Long = 1_700_000_100_000L,
    ): GoogleDriveSyncEngine {
        val client = HttpGoogleDriveClient(
            tokenProvider = DriveTokenProvider { server.validAccessToken },
            apiBase = server.apiBase,
            uploadBase = server.uploadBase,
            baseBackoffMs = 10L,
            maxJitterMs = 0L,
        )
        return GoogleDriveSyncEngine(
            driveApi = client,
            saveFileManager = saves,
            baselineFile = File(root, SYNC_STATE_FILE_NAME),
            clock = { clockMs },
        )
    }
}

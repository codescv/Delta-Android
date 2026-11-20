package com.codescv.delta.sync

import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateContainer
import com.codescv.delta.saves.SaveStateHeader
import com.codescv.delta.saves.SaveStateInfo
import com.codescv.delta.saves.SaveStateType
import com.codescv.delta.saves.SaveTransferException
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.settings.PreferencesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class GoogleDriveSyncConflictPickerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun conflictPolicy_defaultsToAskAndPreservesExistingRawValues() {
        assertEquals(ConflictPolicy.ASK, ConflictPolicy.fromRaw(null))
        assertEquals(ConflictPolicy.ASK, ConflictPolicy.fromRaw(""))
        assertEquals(ConflictPolicy.ASK, ConflictPolicy.fromRaw("unknown"))
        assertEquals(ConflictPolicy.ASK, ConflictPolicy.fromRaw("ask"))
        assertEquals("Ask Every Time", ConflictPolicy.ASK.title)
        assertEquals(ConflictPolicy.NEWEST_WINS, ConflictPolicy.fromRaw("newestWins"))
        assertEquals(ConflictPolicy.PREFER_LOCAL, ConflictPolicy.fromRaw("preferLocal"))
        assertEquals(ConflictPolicy.PREFER_CLOUD, ConflictPolicy.fromRaw("preferCloud"))
        assertEquals(ConflictPolicy.ASK, SyncOptions().conflictPolicy)
        assertEquals("ask", AppPreferences().syncConflictPolicy)
    }

    @Test
    fun askPolicy_batterySaveConflict_leavesBothIntactAndContinuesSyncingOtherGames() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("askBattery")
            val saves = SaveFileManager(root)

            val conflictRom = makeRom(root, "Pokemon Emerald.gba", "gba", 0x11111111L)
            val uploadRom = makeRom(root, "Tetris.gb", "gb", 0x22222222L)
            val downloadRom = makeRom(root, "Super Metroid.sfc", "snes", 0x33333333L)

            val localConflictBytes = "LOCAL-EMERALD-SAVE".toByteArray()
            val remoteConflictBytes = "REMOTE-EMERALD-SAVE-LONGER".toByteArray()
            val localOnlyBytes = "TETRIS-LOCAL".toByteArray()
            val remoteOnlyBytes = "METROID-CLOUD".toByteArray()

            saves.writeSaveRam(localConflictBytes, conflictRom)
            saves.saveRamFile(conflictRom).setLastModified(1_700_000_010_000L)
            saves.writeSaveRam(localOnlyBytes, uploadRom)

            val rootFolder = server.seedFolder("Delta Sync")
            val gamesFolder = server.seedFolder("games", rootFolder.id)
            val conflictFolder = server.seedFolder("gba_11111111", gamesFolder.id)
            val remoteSrm = server.seedFile(
                conflictFolder.id,
                "save.srm",
                remoteConflictBytes,
                modifiedTimeMs = 1_700_000_050_000L,
            )
            val meta = RemoteGameMeta(
                schemaVersion = 1,
                gameKey = "gba_11111111",
                gameName = "Pokemon Emerald",
                systemId = "gba",
                crc32 = "11111111",
                updatedAtMs = 1_700_000_050_000L,
                updatedByDevice = "iPhone 16 Pro",
            )
            server.seedFile(
                conflictFolder.id,
                "meta.json",
                syncPrettyJson.encodeToString(RemoteGameMeta.serializer(), meta).toByteArray(Charsets.UTF_8),
                mimeType = "application/json",
                modifiedTimeMs = 1_700_000_050_000L,
            )

            val downloadFolder = server.seedFolder("snes_33333333", gamesFolder.id)
            server.seedFile(downloadFolder.id, "save.srm", remoteOnlyBytes, modifiedTimeMs = 1_700_000_020_000L)

            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_100_000L)
            val res = engine.syncAll(
                listOf(conflictRom, uploadRom, downloadRom),
                SyncOptions(conflictPolicy = ConflictPolicy.ASK),
            )

            assertTrue(res.errors.isEmpty())
            assertEquals(1, res.conflicts)
            assertEquals(1, res.pendingConflicts.size)
            assertEquals(1, res.uploaded)
            assertEquals(1, res.downloaded)

            // Conflicting game: both local and remote completely untouched, no .bak created.
            assertArrayEquals(localConflictBytes, saves.readSaveRam(conflictRom))
            assertArrayEquals(remoteConflictBytes, remoteSrm.bytes)
            val backups = saves.saveRamFile(conflictRom).parentFile!!
                .listFiles { f -> f.name.contains(".conflict-") }
            assertEquals(0, backups?.size ?: 0)

            // Conflict metadata populated accurately (including remoteDeviceName from meta.json).
            val c = res.pendingConflicts.single()
            assertEquals("games/gba_11111111/save.srm", c.id)
            assertEquals("gba_11111111", c.gameKey)
            assertEquals(conflictRom.title, c.gameName)
            assertEquals("gba", c.systemId)
            assertEquals(conflictRom.id, c.romId)
            assertEquals(SyncConflictKind.BATTERY_SAVE, c.kind)
            assertNull(c.stateId)
            assertEquals(localConflictBytes.size.toLong(), c.localSizeBytes)
            assertEquals(remoteConflictBytes.size.toLong(), c.remoteSizeBytes)
            assertEquals(1_700_000_010_000L, c.localMtimeMs)
            assertEquals(1_700_000_050_000L, c.remoteMtimeMs)
            assertEquals("iPhone 16 Pro", c.remoteDeviceName)
            assertEquals(remoteSrm.id, c.remoteFileId)
            assertEquals(conflictFolder.id, c.remoteParentFolderId)

            // Non-conflicting games synced normally!
            assertArrayEquals(
                localOnlyBytes,
                server.findByPath("Delta Sync", "games", "gb_22222222", "save.srm")!!.bytes,
            )
            assertArrayEquals(remoteOnlyBytes, saves.readSaveRam(downloadRom))
        }
    }

    @Test
    fun askPolicy_saveStateConflict_defersStateAndPngAndPreservesBothIndicesWhileSyncingOthers() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("askState")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Advance Wars.gba", "gba", 0x44445555L)
            val header = SaveStateHeader("gba", "gba", 0x44445555L, 1000.0)

            // Local conflicting state "gen1" + local-only "gen-local"
            saves.writeState(rom, "LOCAL-GEN1".toByteArray(), header, "LOCAL-PNG1".toByteArray(), SaveStateType.GENERAL, id = "gen1", name = "Local Boss")
            saves.writeState(rom, "LOCAL-ONLY".toByteArray(), header, null, SaveStateType.GENERAL, id = "gen-local", name = "Local Extra")
            saves.stateFile(rom, "gen1").setLastModified(1_700_000_005_000L)
            saves.replaceStateIndex(
                rom,
                listOf(
                    SaveStateInfo("gen1", "general", "Local Boss", false, 1000L, 1_700_000_005_000L, "gba", "gba"),
                    SaveStateInfo("gen-local", "general", "Local Extra", false, 2000L, 1_700_000_006_000L, "gba", "gba"),
                ),
            )

            // Remote conflicting state "gen1" (different payload + png + metadata) + remote-only "gen-remote"
            val rootFolder = server.seedFolder("Delta Sync")
            val gamesFolder = server.seedFolder("games", rootFolder.id)
            val gameFolder = server.seedFolder("gba_44445555", gamesFolder.id)
            val statesFolder = server.seedFolder("states", gameFolder.id)
            val remoteGen1Svs = SaveStateContainer.encode("REMOTE-GEN1".toByteArray(), header)
            val remoteGen1Png = "REMOTE-PNG1".toByteArray()
            val remoteOnlySvs = SaveStateContainer.encode("REMOTE-ONLY".toByteArray(), header)

            val remoteGen1Node = server.seedFile(statesFolder.id, "gen1.svs", remoteGen1Svs, modifiedTimeMs = 1_700_000_020_000L)
            val remotePng1Node = server.seedFile(statesFolder.id, "gen1.png", remoteGen1Png, mimeType = "image/png", modifiedTimeMs = 1_700_000_020_000L)
            server.seedFile(statesFolder.id, "gen-remote.svs", remoteOnlySvs, modifiedTimeMs = 1_700_000_025_000L)
            val remoteIndex = listOf(
                RemoteSaveStateEntry("gen1", "general", "Cloud Boss", true, 1000L, 1_700_000_020_000L, "gba", "gba"),
                RemoteSaveStateEntry("gen-remote", "general", "Cloud Extra", false, 3000L, 1_700_000_025_000L, "gba", "gba"),
            )
            server.seedFile(
                gameFolder.id,
                "states_index.json",
                syncPrettyJson.encodeToString(ListSerializer(RemoteSaveStateEntry.serializer()), remoteIndex)
                    .toByteArray(Charsets.UTF_8),
                mimeType = "application/json",
                modifiedTimeMs = 1_700_000_025_000L,
            )

            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_100_000L)
            val res = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))

            assertTrue(res.errors.isEmpty())
            // Only ONE conflict (for gen1.svs) — .png MUST NOT generate a separate conflict!
            assertEquals(1, res.pendingConflicts.size)
            val c = res.pendingConflicts.single()
            assertEquals(SyncConflictKind.SAVE_STATE, c.kind)
            assertEquals("gen1", c.stateId)
            assertEquals("Local Boss", c.stateLabel)
            assertEquals("games/gba_44445555/states/gen1.svs", c.id)

            // Both sides of gen1 (.svs and .png) remain completely untouched.
            assertArrayEquals("LOCAL-GEN1".toByteArray(), saves.readState(rom, "gen1").second)
            assertArrayEquals("LOCAL-PNG1".toByteArray(), saves.screenshotFile(rom, "gen1").readBytes())
            assertArrayEquals(remoteGen1Svs, remoteGen1Node.bytes)
            assertArrayEquals(remoteGen1Png, remotePng1Node.bytes)

            // Local index still has Local Boss for gen1, remote index still has Cloud Boss for gen1.
            assertEquals("Local Boss", saves.state(rom, "gen1")!!.name)
            val remoteIdxAfter = syncJson.decodeFromString<List<RemoteSaveStateEntry>>(
                server.findByPath("Delta Sync", "games", "gba_44445555", "states_index.json")!!.bytes.toString(Charsets.UTF_8),
            ).associateBy { it.id }
            assertEquals("Cloud Boss", remoteIdxAfter["gen1"]!!.name)

            // Non-conflicting states synced in both directions!
            assertNotNull(server.findByPath("Delta Sync", "games", "gba_44445555", "states", "gen-local.svs"))
            assertArrayEquals("REMOTE-ONLY".toByteArray(), saves.readState(rom, "gen-remote").second)
        }
    }

    @Test
    fun resolveConflict_keepLocal_batterySave_uploadsAndUpdatesBaseline_nextSyncZeroConflicts() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("resolveKeepLocalSrm")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Zelda.gba", "gba", 0xABCD1234L)
            saves.writeSaveRam("V0".toByteArray(), rom)

            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_000_000L)
            assertEquals(1, engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK)).uploaded)

            // Diverge both sides
            val localBytes = "LOCAL-CHOSEN".toByteArray()
            val remoteBytes = "REMOTE-DISCARDED".toByteArray()
            saves.writeSaveRam(localBytes, rom)
            saves.saveRamFile(rom).setLastModified(1_700_000_010_000L)
            val remoteSrm = server.findByPath("Delta Sync", "games", "gba_abcd1234", "save.srm")!!
            remoteSrm.bytes = remoteBytes
            remoteSrm.modifiedTimeMs = 1_700_000_050_000L

            val firstRes = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(1, firstRes.pendingConflicts.size)

            engine.resolveConflict(
                conflict = firstRes.pendingConflicts.single(),
                choice = ConflictChoice.KEEP_LOCAL,
                roms = listOf(rom),
                options = SyncOptions(deviceName = "Pixel 9 Pro"),
            )

            // Cloud updated with local bytes and updated meta.json
            assertArrayEquals(localBytes, remoteSrm.bytes)
            val metaNode = server.findByPath("Delta Sync", "games", "gba_abcd1234", "meta.json")!!
            val meta = syncJson.decodeFromString<RemoteGameMeta>(metaNode.bytes.toString(Charsets.UTF_8))
            assertEquals("Pixel 9 Pro", meta.updatedByDevice)

            // Next sync under ASK has 0 conflicts and 0 transfers!
            val nextRes = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(0, nextRes.conflicts)
            assertTrue(nextRes.pendingConflicts.isEmpty())
            assertEquals(0, nextRes.uploaded)
            assertEquals(0, nextRes.downloaded)
        }
    }

    @Test
    fun resolveConflict_useCloud_batterySave_backsUpWritesCloudInvalidatesAutoStates_nextSyncZeroConflicts() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("resolveUseCloudSrm")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Golden Sun.gba", "gba", 0x55667788L)
            val header = SaveStateHeader("gba", "gba", 0x55667788L, 1000.0)

            saves.writeSaveRam("V0".toByteArray(), rom)
            saves.writeState(rom, "AUTO-V0".toByteArray(), header, null, SaveStateType.AUTO, id = "auto1")
            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_100_000L)
            engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))

            // Diverge save.srm on both sides
            val localBytes = "LOCAL-OLD-SAVE".toByteArray()
            val remoteBytes = "CLOUD-NEW-SAVE".toByteArray()
            saves.writeSaveRam(localBytes, rom)
            saves.saveRamFile(rom).setLastModified(1_700_000_010_000L)
            val remoteSrm = server.findByPath("Delta Sync", "games", "gba_55667788", "save.srm")!!
            remoteSrm.bytes = remoteBytes
            remoteSrm.modifiedTimeMs = 1_700_000_050_000L

            val syncRes = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(1, syncRes.pendingConflicts.size)
            assertNotNull("Auto-state should still exist before user chooses USE_CLOUD", saves.latestAutoState(rom))

            engine.resolveConflict(
                conflict = syncRes.pendingConflicts.single(),
                choice = ConflictChoice.USE_CLOUD,
                roms = listOf(rom),
            )

            // Local file now has cloud bytes
            assertArrayEquals(remoteBytes, saves.readSaveRam(rom))
            // Conflict backup exists with old local bytes
            val backups = saves.saveRamFile(rom).parentFile!!
                .listFiles { f -> f.name.startsWith("Golden Sun.srm.conflict-") && f.name.endsWith(".bak") }
            assertNotNull(backups)
            assertEquals(1, backups!!.size)
            assertArrayEquals(localBytes, backups[0].readBytes())
            // Stale local auto-states invalidated
            assertNull("Stale auto-state should be invalidated on USE_CLOUD for BATTERY_SAVE", saves.latestAutoState(rom))

            // Next sync has 0 conflicts and does NOT resurrect stale auto-state
            val nextRes = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(0, nextRes.conflicts)
            assertTrue(nextRes.pendingConflicts.isEmpty())
            assertNull(saves.latestAutoState(rom))
        }
    }

    @Test
    fun resolveConflict_saveState_keepLocalAndUseCloud_syncsPngAndIndicesAndNextSyncClean() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("resolveStateBoth")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Metroid Fusion.gba", "gba", 0xCAFE1234L)
            val header = SaveStateHeader("gba", "gba", 0xCAFE1234L, 1000.0)

            // Two conflicting states: s1 (will KEEP_LOCAL) and s2 (will USE_CLOUD)
            saves.writeState(rom, "L-S1".toByteArray(), header, "L-PNG1".toByteArray(), SaveStateType.GENERAL, id = "s1", name = "Local S1")
            saves.writeState(rom, "L-S2".toByteArray(), header, "L-PNG2".toByteArray(), SaveStateType.GENERAL, id = "s2", name = "Local S2")
            saves.stateFile(rom, "s1").setLastModified(1_700_000_010_000L)
            saves.stateFile(rom, "s2").setLastModified(1_700_000_010_000L)

            val r = server.seedFolder("Delta Sync")
            val g = server.seedFolder("games", r.id)
            val gameFolder = server.seedFolder("gba_cafe1234", g.id)
            val statesFolder = server.seedFolder("states", gameFolder.id)
            val remoteS1Bytes = SaveStateContainer.encode("R-S1".toByteArray(), header)
            val remoteS2Bytes = SaveStateContainer.encode("R-S2".toByteArray(), header)
            val remoteS1Node = server.seedFile(statesFolder.id, "s1.svs", remoteS1Bytes, modifiedTimeMs = 1_700_000_020_000L)
            val remotePng1Node = server.seedFile(statesFolder.id, "s1.png", "R-PNG1".toByteArray(), "image/png", 1_700_000_020_000L)
            server.seedFile(statesFolder.id, "s2.svs", remoteS2Bytes, modifiedTimeMs = 1_700_000_030_000L)
            server.seedFile(statesFolder.id, "s2.png", "R-PNG2".toByteArray(), "image/png", 1_700_000_030_000L)
            val remoteIndex = listOf(
                RemoteSaveStateEntry("s1", "general", "Remote S1", false, 1000L, 1_700_000_020_000L, "gba", "gba"),
                RemoteSaveStateEntry("s2", "general", "Remote S2", true, 1000L, 1_700_000_030_000L, "gba", "gba"),
            )
            server.seedFile(
                gameFolder.id,
                "states_index.json",
                syncPrettyJson.encodeToString(ListSerializer(RemoteSaveStateEntry.serializer()), remoteIndex)
                    .toByteArray(Charsets.UTF_8),
                mimeType = "application/json",
                modifiedTimeMs = 1_700_000_030_000L,
            )

            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_200_000L)
            val firstRes = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(2, firstRes.pendingConflicts.size)
            val c1 = firstRes.pendingConflicts.first { it.stateId == "s1" }
            val c2 = firstRes.pendingConflicts.first { it.stateId == "s2" }

            // Resolve s1 -> KEEP_LOCAL
            engine.resolveConflict(c1, ConflictChoice.KEEP_LOCAL, listOf(rom), SyncOptions(deviceName = "Pixel 9"))
            assertArrayEquals("L-S1".toByteArray(), SaveStateContainer.decode(remoteS1Node.bytes).second)
            assertArrayEquals("L-PNG1".toByteArray(), remotePng1Node.bytes)
            val remoteIdxAfterS1 = syncJson.decodeFromString<List<RemoteSaveStateEntry>>(
                server.findByPath("Delta Sync", "games", "gba_cafe1234", "states_index.json")!!.bytes.toString(Charsets.UTF_8),
            ).associateBy { it.id }
            assertEquals("Local S1", remoteIdxAfterS1["s1"]!!.name)

            // Resolve s2 -> USE_CLOUD
            val oldLocalS2 = saves.stateFile(rom, "s2").readBytes()
            engine.resolveConflict(c2, ConflictChoice.USE_CLOUD, listOf(rom))
            assertArrayEquals("R-S2".toByteArray(), saves.readState(rom, "s2").second)
            assertArrayEquals("R-PNG2".toByteArray(), saves.screenshotFile(rom, "s2").readBytes())
            assertEquals("Remote S2", saves.state(rom, "s2")!!.name)
            assertTrue(saves.state(rom, "s2")!!.locked)
            val s2Backups = saves.stateDirectory(rom)
                .listFiles { f -> f.name.startsWith("s2.state.conflict-") && f.name.endsWith(".bak") }
            assertEquals(1, s2Backups?.size ?: 0)
            assertArrayEquals(oldLocalS2, s2Backups!![0].readBytes())

            // Next sync has 0 conflicts and 0 transfers!
            val nextRes = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            assertEquals(0, nextRes.conflicts)
            assertTrue(nextRes.pendingConflicts.isEmpty())
            assertEquals(0, nextRes.uploaded)
            assertEquals(0, nextRes.downloaded)
        }
    }

    @Test
    fun resolveConflict_useCloud_refusesToOverwriteWhenGameIsRunning() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("runningResolve")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Fire Emblem.gba", "gba", 0x90909090L)
            val localBytes = "LOCAL-ACTIVE-GAME".toByteArray()
            saves.writeSaveRam(localBytes, rom)
            saves.saveRamFile(rom).setLastModified(1_700_000_001_000L)

            val r = server.seedFolder("Delta Sync")
            val g = server.seedFolder("games", r.id)
            val gameFolder = server.seedFolder("gba_90909090", g.id)
            server.seedFile(gameFolder.id, "save.srm", "CLOUD-SAVE".toByteArray(), modifiedTimeMs = 1_700_000_050_000L)

            val running = AtomicBoolean(false)
            val engine = makeEngine(server, saves, root, isGameRunning = { running.get() })
            val syncRes = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.ASK))
            val conflict = syncRes.pendingConflicts.single()

            // Mark game running -> USE_CLOUD must fail cleanly without touching local save or creating .bak
            running.set(true)
            try {
                engine.resolveConflict(conflict, ConflictChoice.USE_CLOUD, listOf(rom))
                fail("Expected SaveTransferException when game is running")
            } catch (e: SaveTransferException) {
                assertTrue(e.message!!.contains("running", ignoreCase = true))
            }
            assertArrayEquals(localBytes, saves.readSaveRam(rom))
            val backups = saves.saveRamFile(rom).parentFile!!
                .listFiles { f -> f.name.contains(".conflict-") }
            assertEquals(0, backups?.size ?: 0)
        }
    }

    @Test
    fun syncManager_pendingConflicts_dismissShowResolveAndSignOutClears() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("mgrConflicts")
            val saves = SaveFileManager(root)
            val prefs = PreferencesStore(File(root, "preferences.json"))
            val rom = makeRom(root, "Kirby.gba", "gba", 0x77776666L)
            saves.writeSaveRam("LOCAL-V1".toByteArray(), rom)
            saves.saveRamFile(rom).setLastModified(1_700_000_001_000L)

            val r = server.seedFolder("Delta Sync")
            val g = server.seedFolder("games", r.id)
            val gameFolder = server.seedFolder("gba_77776666", g.id)
            val remoteSrm = server.seedFile(gameFolder.id, "save.srm", "CLOUD-V1".toByteArray(), modifiedTimeMs = 1_700_000_050_000L)

            val secretStore = InMemorySyncSecretStore(
                StoredOAuthCredentials(
                    clientId = "cid",
                    accessToken = server.validAccessToken,
                    refreshToken = "rt",
                    expiresAtMs = Long.MAX_VALUE / 2,
                    accountEmail = "player@example.com",
                    accountName = "Player",
                ),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val manager = GoogleDriveSyncManager(
                    secretStore = secretStore,
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

                // Default policy is ASK -> syncNow surfaces conflict in uiState
                val res = manager.syncNow()
                assertEquals(1, res.pendingConflicts.size)
                assertEquals(1, manager.uiState.value.pendingConflicts.size)
                assertFalse(manager.uiState.value.conflictPromptDismissed)

                // Dismiss hides modal while keeping pendingConflicts intact
                manager.dismissConflictPrompt()
                assertTrue(manager.uiState.value.conflictPromptDismissed)
                assertEquals(1, manager.uiState.value.pendingConflicts.size)

                // Manual reopen unhides modal
                manager.showConflictPrompt()
                assertFalse(manager.uiState.value.conflictPromptDismissed)

                // If game is running, USE_CLOUD fails cleanly and keeps conflict in pendingConflicts
                saves.markRunning(rom, true)
                val failRes = manager.resolveConflict(manager.uiState.value.pendingConflicts.single(), ConflictChoice.USE_CLOUD)
                assertTrue(failRes.isFailure)
                assertEquals(1, manager.uiState.value.pendingConflicts.size)
                assertNull(manager.uiState.value.resolvingConflictId)
                saves.markRunning(rom, false)

                // KEEP_LOCAL succeeds and clears conflict from pendingConflicts
                val okRes = manager.resolveConflict(manager.uiState.value.pendingConflicts.single(), ConflictChoice.KEEP_LOCAL)
                assertTrue(okRes.isSuccess)
                assertTrue(manager.uiState.value.pendingConflicts.isEmpty())
                assertNull(manager.uiState.value.resolvingConflictId)
                assertArrayEquals("LOCAL-V1".toByteArray(), remoteSrm.bytes)

                // Create another conflict and verify signOut clears pendingConflicts
                saves.writeSaveRam("LOCAL-V2".toByteArray(), rom)
                remoteSrm.bytes = "CLOUD-V2".toByteArray()
                remoteSrm.modifiedTimeMs = 1_700_000_090_000L
                manager.syncNow()
                assertEquals(1, manager.uiState.value.pendingConflicts.size)

                manager.signOut()
                assertFalse(manager.uiState.value.isSignedIn)
                assertTrue(manager.uiState.value.pendingConflicts.isEmpty())
                assertNull(manager.uiState.value.resolvingConflictId)
                assertFalse(manager.uiState.value.conflictPromptDismissed)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun syncManager_useCloudOnBatterySave_prunesInvalidatedAutoStateConflict() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("mgrPruneAuto")
            val saves = SaveFileManager(root)
            val prefs = PreferencesStore(File(root, "preferences.json"))
            val rom = makeRom(root, "Mother 3.gba", "gba", 0x12123434L)
            val header = SaveStateHeader("gba", "gba", 0x12123434L, 1000.0)

            saves.writeSaveRam("LOCAL-SRM".toByteArray(), rom)
            saves.writeState(rom, "LOCAL-AUTO".toByteArray(), header, null, SaveStateType.AUTO, id = "auto1")

            val r = server.seedFolder("Delta Sync")
            val g = server.seedFolder("games", r.id)
            val gameFolder = server.seedFolder("gba_12123434", g.id)
            val statesFolder = server.seedFolder("states", gameFolder.id)
            server.seedFile(gameFolder.id, "save.srm", "CLOUD-SRM".toByteArray(), modifiedTimeMs = 1_700_000_050_000L)
            server.seedFile(statesFolder.id, "auto1.svs", SaveStateContainer.encode("CLOUD-AUTO".toByteArray(), header), modifiedTimeMs = 1_700_000_050_000L)

            val secretStore = InMemorySyncSecretStore(
                StoredOAuthCredentials(
                    clientId = "cid",
                    accessToken = server.validAccessToken,
                    refreshToken = "rt",
                    expiresAtMs = Long.MAX_VALUE / 2,
                    accountEmail = "player@example.com",
                ),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val manager = GoogleDriveSyncManager(
                    secretStore = secretStore,
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
                val res = manager.syncNow()
                assertEquals(2, res.pendingConflicts.size)

                val srmConflict = res.pendingConflicts.first { it.kind == SyncConflictKind.BATTERY_SAVE }
                assertTrue(manager.resolveConflict(srmConflict, ConflictChoice.USE_CLOUD).isSuccess)
                // Local auto1 was deleted by USE_CLOUD on save.srm, so its stale conflict is pruned too!
                assertTrue(manager.uiState.value.pendingConflicts.isEmpty())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun syncManager_signOutDuringInFlightResolveConflict_doesNotResurrectBaselineOrState() = runBlocking {
        val root = tempFolder.newFolder("mgrSignOutResolveRace")
        val saves = SaveFileManager(root)
        val prefs = PreferencesStore(File(root, "preferences.json"))
        val rom = makeRom(root, "Zelda.gba", "gba", 0x88881111L)
        saves.writeSaveRam("LOCAL".toByteArray(), rom)

        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val fakeDrive = object : DriveApi {
            override suspend fun findOrCreateFolder(name: String, parentId: String?) =
                DriveFile(id = "f_$name", name = name, mimeType = DRIVE_FOLDER_MIME)

            override suspend fun listChildren(folderId: String) = emptyList<DriveFile>()
            override suspend fun downloadFile(fileId: String): ByteArray {
                entered.complete(Unit)
                proceed.await()
                return "CLOUD".toByteArray()
            }

            override suspend fun uploadFile(
                parentId: String,
                name: String,
                mimeType: String,
                content: ByteArray,
                existingFileId: String?,
                modifiedTimeMs: Long?,
            ): DriveFile = DriveFile(id = "f1", name = name, mimeType = mimeType)
        }

        val secretStore = InMemorySyncSecretStore(
            StoredOAuthCredentials(
                clientId = "cid",
                accessToken = "tok",
                refreshToken = "rt",
                expiresAtMs = Long.MAX_VALUE / 2,
                accountEmail = "u@example.com",
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = GoogleDriveSyncManager(
                secretStore = secretStore,
                preferences = prefs,
                romsProvider = { listOf(rom) },
                saves = saves,
                filesDir = root,
                scope = scope,
                driveApiFactory = { fakeDrive },
            )
            val conflict = SyncConflict(
                id = "games/gba_88881111/save.srm",
                gameKey = "gba_88881111",
                gameName = rom.title,
                systemId = "gba",
                romId = rom.id,
                kind = SyncConflictKind.BATTERY_SAVE,
                localMtimeMs = 1_000L,
                localSizeBytes = 5L,
                remoteMtimeMs = 2_000L,
                remoteSizeBytes = 5L,
                remoteFileId = "remote_srm_1",
                remoteParentFolderId = "game_folder_1",
            )
            val resolveJob = async(Dispatchers.IO) { runCatching { manager.resolveConflict(conflict, ConflictChoice.USE_CLOUD) } }
            withTimeout(2_000L) { entered.await() }
            manager.signOut()
            proceed.complete(Unit)
            resolveJob.await()

            assertFalse(manager.uiState.value.isSignedIn)
            assertTrue(manager.uiState.value.pendingConflicts.isEmpty())
            assertFalse(File(root, SYNC_STATE_FILE_NAME).exists())
        } finally {
            scope.cancel()
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
        clockMs: Long = 1_700_000_000_000L,
        isGameRunning: (RomEntry) -> Boolean = { saves.isRunning(it) },
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
            isGameRunning = isGameRunning,
            clock = { clockMs },
        )
    }
}

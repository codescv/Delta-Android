package com.codescv.delta.sync

import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateContainer
import com.codescv.delta.saves.SaveStateHeader
import com.codescv.delta.saves.SaveStateType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial edge cases written by android-tester (cross-platform, data-loss, TOCTOU).
 */
class GoogleDriveSyncAdversarialTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Delegating [DriveApi] that lets tests hook downloads / count uploads by name. */
    private class SpyDriveApi(
        private val delegate: DriveApi,
        val afterDownload: (fileId: String) -> Unit = {},
    ) : DriveApi {
        val uploadsByName = HashMap<String, AtomicInteger>()

        override suspend fun findOrCreateFolder(name: String, parentId: String?) =
            delegate.findOrCreateFolder(name, parentId)

        override suspend fun listChildren(folderId: String) = delegate.listChildren(folderId)

        override suspend fun downloadFile(fileId: String): ByteArray {
            val bytes = delegate.downloadFile(fileId)
            afterDownload(fileId)
            return bytes
        }

        override suspend fun uploadFile(
            parentId: String,
            name: String,
            mimeType: String,
            content: ByteArray,
            existingFileId: String?,
            modifiedTimeMs: Long?,
        ): DriveFile {
            synchronized(uploadsByName) { uploadsByName.getOrPut(name) { AtomicInteger() }.incrementAndGet() }
            return delegate.uploadFile(parentId, name, mimeType, content, existingFileId, modifiedTimeMs)
        }

        fun uploads(name: String) = synchronized(uploadsByName) { uploadsByName[name]?.get() ?: 0 }
    }

    /**
     * iOS decodes `states_index.json` leniently (missing `kind`/`coreId`/`systemId`/timestamps get
     * defaults). If Android cannot decode ONE entry it must NOT treat the whole remote index as empty
     * and then overwrite the remote index with a local-only list (silently orphaning every remote state).
     */
    @Test
    fun remoteStatesIndex_withEntryMissingOptionalFields_isNotWipedFromRemote() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("lenientIndex")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Metroid Fusion.gba", "gba", 0x11112222L)
            val header = SaveStateHeader("gba", "gba", 0x11112222L, 1000.0)
            saves.writeState(rom, "LOCAL".toByteArray(), header, null, SaveStateType.GENERAL, id = "local-gen", name = "Local")

            val gameFolder = seedGameFolder(server, "gba_11112222")
            val statesFolder = server.seedFolder("states", gameFolder.id)
            val remoteSvs = SaveStateContainer.encode("REMOTE".toByteArray(), header)
            server.seedFile(statesFolder.id, "slot1.svs", remoteSvs, modifiedTimeMs = 1_700_000_010_000L)
            // Entry lacks `coreId` / `systemId` (iOS's decoder defaults these to "unknown" / "").
            val remoteIndexJson = """[{"id":"slot1","kind":"general","name":"Slot 1","locked":false,""" +
                """"createdAtMs":1700000010000,"modifiedAtMs":1700000010000}]"""
            server.seedFile(gameFolder.id, "states_index.json", remoteIndexJson.toByteArray(), "application/json", 1_700_000_010_000L)

            makeEngine(server, saves, root).syncAll(listOf(rom))

            val idx = server.findByPath("Delta Sync", "games", "gba_11112222", "states_index.json")!!
            val ids = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(idx.bytes.toString(Charsets.UTF_8)).map { it.groupValues[1] }.toSet()
            assertTrue("Remote entry 'slot1' was wiped from states_index.json: $ids", "slot1" in ids)
            assertTrue("local-gen" in ids)
        }
    }

    /**
     * iOS writes `states_index.json` with `JSONEncoder.sortedKeys` (compact, nil `name` omitted), Android
     * writes pretty-printed declaration order. MD5-of-bytes comparison makes the two platforms rewrite the
     * index on EVERY sync forever even when nothing changed. Compare semantically instead.
     */
    @Test
    fun semanticallyIdenticalIosFormattedIndex_isNotReuploaded() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("pingPong")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Zelda Minish Cap.gba", "gba", 0x33334444L)
            val header = SaveStateHeader("gba", "gba", 0x33334444L, 1000.0)
            saves.writeState(rom, "S1".toByteArray(), header, null, SaveStateType.GENERAL, id = "gen1", name = "Save State 1")

            val spy = SpyDriveApi(makeClient(server))
            val engine = GoogleDriveSyncEngine(spy, saves, File(root, SYNC_STATE_FILE_NAME), { false }, { 1_700_000_000_000L })
            engine.syncAll(listOf(rom))
            assertEquals(1, spy.uploads(STATES_INDEX_REMOTE_NAME))

            // iOS device re-encodes the same single entry with sortedKeys/compact.
            val info = saves.listStates(rom).single()
            val iosBytes = ("""[{"coreId":"${info.coreId}","createdAtMs":${info.createdAt},"id":"gen1","kind":"general",""" +
                """"locked":false,"modifiedAtMs":${info.modifiedAt},"name":"Save State 1","systemId":"gba"}]""").toByteArray()
            server.findByPath("Delta Sync", "games", "gba_33334444", "states_index.json")!!.bytes = iosBytes

            engine.syncAll(listOf(rom))
            assertEquals("Index re-uploaded although semantically unchanged", 1, spy.uploads(STATES_INDEX_REMOTE_NAME))
        }
    }

    /**
     * TOCTOU: `isGameRunning` is checked BEFORE the (slow) network download, then the file is written.
     * If the user launches the game while the download is in flight (EmulatorSession.start reads the .srm
     * and only afterwards calls markRunning), the running game's save gets replaced underneath it and is
     * later clobbered by persistSramIfChanged. The guard must be re-checked right before the local write.
     */
    @Test
    fun runningGuard_reCheckedAfterDownload_beforeOverwritingLocal() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("toctou")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Pokemon Ruby.gba", "gba", 0x55556666L)
            saves.writeSaveRam("V1".toByteArray(), rom)

            val running = AtomicBoolean(false)
            val spy = SpyDriveApi(makeClient(server), afterDownload = { running.set(true) })
            val engine = GoogleDriveSyncEngine(spy, saves, File(root, SYNC_STATE_FILE_NAME), { running.get() }, { 1_700_000_000_000L })
            assertEquals(1, engine.syncAll(listOf(rom)).uploaded)

            val remote = server.findByPath("Delta Sync", "games", "gba_55556666", "save.srm")!!
            remote.bytes = "V2-CLOUD".toByteArray()
            remote.modifiedTimeMs = 1_700_000_050_000L

            // Game gets launched while the download is in flight.
            engine.syncAll(listOf(rom))
            assertArrayEquals(
                "Running game's .srm was overwritten after it started",
                "V1".toByteArray(),
                saves.readSaveRam(rom),
            )
        }
    }

    /** Path-traversal ids from a hostile/corrupt remote index must never escape the state directory. */
    @Test
    fun hostileStateIds_neverWriteOutsideStateDirectory() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("traversal")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Kirby.gba", "gba", 0x77778888L)
            val gameFolder = seedGameFolder(server, "gba_77778888")
            val statesFolder = server.seedFolder("states", gameFolder.id)
            for (bad in listOf("../../evil", "..", "a/b", "..\\x")) {
                server.seedFile(statesFolder.id, "$bad.svs", "X".toByteArray())
            }
            val idx = """[
              {"id":"../../evil","kind":"general","locked":false,"createdAtMs":1,"modifiedAtMs":1,"coreId":"gba","systemId":"gba"},
              {"id":"..","kind":"general","locked":false,"createdAtMs":1,"modifiedAtMs":1,"coreId":"gba","systemId":"gba"},
              {"id":"a/b","kind":"general","locked":false,"createdAtMs":1,"modifiedAtMs":1,"coreId":"gba","systemId":"gba"},
              {"id":"..\\x","kind":"general","locked":false,"createdAtMs":1,"modifiedAtMs":1,"coreId":"gba","systemId":"gba"}
            ]"""
            server.seedFile(gameFolder.id, "states_index.json", idx.toByteArray(), "application/json")

            val res = makeEngine(server, saves, root).syncAll(listOf(rom))
            assertTrue(res.errors.toString(), res.errors.isEmpty())
            assertEquals(0, res.downloaded)
            assertTrue(!File(root, "evil.state").exists())
            assertTrue(!File(saves.statesDirectory, "evil.state").exists())
            assertTrue(saves.listStates(rom).isEmpty())
        }
    }

    /** #11.3 / D1: a state deleted locally after a sync (remote unchanged) must not be re-downloaded. */
    @Test
    fun locallyDeletedState_isNotResurrectedFromCloud() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("noResurrect")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Castlevania.gba", "gba", 0x99990000L)
            val header = SaveStateHeader("gba", "gba", 0x99990000L, 1000.0)
            saves.writeState(rom, "G".toByteArray(), header, "P".toByteArray(), SaveStateType.GENERAL, id = "gen-del", name = "Del")
            val engine = makeEngine(server, saves, root)
            engine.syncAll(listOf(rom))
            assertTrue(server.findByPath("Delta Sync", "games", "gba_99990000", "states", "gen-del.svs") != null)

            saves.deleteState(rom, "gen-del")
            val res = engine.syncAll(listOf(rom))
            assertEquals(0, res.downloaded)
            assertTrue("Deleted state resurrected", !saves.stateFile(rom, "gen-del").exists())
            assertTrue(saves.listStates(rom).none { it.id == "gen-del" })
            // and stays deleted on the following sync
            engine.syncAll(listOf(rom))
            assertTrue(!saves.stateFile(rom, "gen-del").exists())
        }
    }

    /** #11.1: downloading a newer cloud save.srm must drop stale local auto states (same as importSaveRam). */
    @Test
    fun cloudSaveRamDownload_invalidatesStaleLocalAutoStates() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("staleAuto")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Golden Sun 2.gba", "gba", 0x24682468L)
            val header = SaveStateHeader("gba", "gba", 0x24682468L, 1000.0)
            saves.writeSaveRam("SRAM-V1".toByteArray(), rom)
            saves.writeState(rom, "AUTO-V1".toByteArray(), header, null, SaveStateType.AUTO)
            val engine = makeEngine(server, saves, root)
            engine.syncAll(listOf(rom))

            // Another device (e.g. iOS, which never touches auto1/auto2) uploads newer SRAM only.
            val remote = server.findByPath("Delta Sync", "games", "gba_24682468", "save.srm")!!
            remote.bytes = "SRAM-V2-CLOUD".toByteArray()
            remote.modifiedTimeMs = 1_700_000_090_000L

            engine.syncAll(listOf(rom))
            assertArrayEquals("SRAM-V2-CLOUD".toByteArray(), saves.readSaveRam(rom))
            assertTrue("Stale auto state would roll back cloud SRAM on resume", saves.latestAutoState(rom) == null)
            engine.syncAll(listOf(rom))
            assertTrue("Stale auto state resurrected", saves.latestAutoState(rom) == null)
        }
    }

    /** #11.2: switching to a different Drive (new root folder id) must not reuse the old baseline. */
    @Test
    fun accountSwitch_doesNotSilentlyOverwriteLocalSave() = runBlocking {
        val root = tempFolder.newFolder("acctSwitch")
        val saves = SaveFileManager(root)
        val rom = makeRom(root, "Fire Emblem.gba", "gba", 0x13571357L)
        val localBytes = "LOCAL-PROGRESS".toByteArray()
        saves.writeSaveRam(localBytes, rom)
        saves.saveRamFile(rom).setLastModified(1_700_000_050_000L)

        MockDriveHttpServer().use { serverA -> makeEngine(serverA, saves, root).syncAll(listOf(rom)) }

        MockDriveHttpServer().use { serverB ->
            serverB.seedFolder("unrelated") // shift ids so account B's root id differs from A's
            val gameFolder = seedGameFolder(serverB, "gba_13571357")
            serverB.seedFile(gameFolder.id, "save.srm", "ACCOUNT-B-OLD".toByteArray(), modifiedTimeMs = 1_600_000_000_000L)

            makeEngine(serverB, saves, root).syncAll(listOf(rom))
            val dir = saves.saveRamFile(rom).parentFile!!
            val hasBackup = dir.listFiles()?.any { it.name.contains(".conflict-") && it.readBytes().contentEquals(localBytes) } == true
            assertTrue(
                "Local save silently replaced by account B's older save",
                saves.readSaveRam(rom)!!.contentEquals(localBytes) || hasBackup,
            )
        }
    }

    /**
     * Two devices played since the last sync; B's SRAM is newer and wins. A's stale local auto state
     * (consistent with A's *losing* SRAM) must not be pushed to the cloud in the same pass, otherwise any
     * other device resumes from it and rolls back B's progress (then uploads the rollback).
     */
    @Test
    fun losingDevicesStaleAutoState_isNotUploadedWhenCloudSaveRamWins() = runBlocking {
        MockDriveHttpServer().use { server ->
            val header = SaveStateHeader("gba", "gba", 0x0BADF00DL, 1000.0)
            val rootA = tempFolder.newFolder("devA2")
            val savesA = SaveFileManager(rootA)
            val romA = makeRom(rootA, "Mother 3.gba", "gba", 0x0BADF00DL)
            savesA.writeSaveRam("SRAM-V1".toByteArray(), romA)
            savesA.saveRamFile(romA).setLastModified(1_700_000_001_000L)
            savesA.writeState(romA, "AUTO-V1".toByteArray(), header, null, SaveStateType.AUTO, id = "auto1")
            val engineA = makeEngine(server, savesA, rootA)
            engineA.syncAll(listOf(romA))

            val rootB = tempFolder.newFolder("devB2")
            val savesB = SaveFileManager(rootB)
            val romB = makeRom(rootB, "Mother 3.gba", "gba", 0x0BADF00DL)
            val engineB = makeEngine(server, savesB, rootB)
            engineB.syncAll(listOf(romB))
            // B plays later (in-game save only) and uploads newer SRAM.
            savesB.writeSaveRam("SRAM-V2-B".toByteArray(), romB)
            savesB.saveRamFile(romB).setLastModified(1_700_000_020_000L)
            engineB.syncAll(listOf(romB))

            // A plays (earlier than B) and exits -> new SRAM + new auto state (auto2), then syncs.
            savesA.writeSaveRam("SRAM-V2-A".toByteArray(), romA)
            savesA.saveRamFile(romA).setLastModified(1_700_000_010_000L)
            savesA.writeState(romA, "AUTO-V2-A-STALE".toByteArray(), header, null, SaveStateType.AUTO)
            engineA.syncAll(listOf(romA))

            assertArrayEquals("SRAM-V2-B".toByteArray(), savesA.readSaveRam(romA))
            val staleOnCloud = listOf("auto1.svs", "auto2.svs").mapNotNull {
                server.findByPath("Delta Sync", "games", "gba_0badf00d", "states", it)
            }.filter { SaveStateContainer.decode(it.bytes).second.contentEquals("AUTO-V2-A-STALE".toByteArray()) }
            assertTrue("Losing device's stale auto state was uploaded to Drive", staleOnCloud.isEmpty())
        }
    }

    // --------------------------------------------------------------------------- helpers

    private fun seedGameFolder(server: MockDriveHttpServer, key: String): MockDriveHttpServer.StoredNode {
        val r = server.seedFolder("Delta Sync")
        val g = server.seedFolder("games", r.id)
        return server.seedFolder(key, g.id)
    }

    private fun makeRom(root: File, fileName: String, systemId: String, crc32: Long?): RomEntry {
        val romFile = File(File(root, "ROMs").apply { mkdirs() }, fileName)
        romFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        return RomEntry(path = romFile.absolutePath, systemId = systemId, fileSize = romFile.length(), crc32 = crc32)
    }

    private fun makeClient(server: MockDriveHttpServer) = HttpGoogleDriveClient(
        tokenProvider = DriveTokenProvider { server.validAccessToken },
        apiBase = server.apiBase,
        uploadBase = server.uploadBase,
        baseBackoffMs = 10L,
        maxJitterMs = 0L,
    )

    private fun makeEngine(server: MockDriveHttpServer, saves: SaveFileManager, root: File) =
        GoogleDriveSyncEngine(makeClient(server), saves, File(root, SYNC_STATE_FILE_NAME), { false }, { 1_700_000_000_000L })
}

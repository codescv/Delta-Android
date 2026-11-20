package com.codescv.delta.sync

import com.codescv.delta.library.RomEntry
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateHeader
import com.codescv.delta.saves.SaveStateInfo
import com.codescv.delta.saves.SaveStateType
import com.codescv.delta.settings.PreferencesStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class GoogleDriveSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // 1. GameKey formatting (matches iOS Delta 100%)
    // =========================================================================

    @Test
    fun gameKey_usesLowercaseSystemAnd8HexCrc32() {
        assertEquals("gba_0abcdef1", GameKey.compute("GBA", 0x0ABCDEF1L, "Pokemon Emerald"))
        assertEquals("snes_deadbeef", GameKey.compute("snes", 0xDEADBEEFL, "Super Metroid (USA)"))
        assertEquals("nds_00000042", GameKey.compute("NDS", 0x42L, "New Super Mario Bros"))
    }

    @Test
    fun gameKey_fallsBackToSanitizedStemWhenCrcMissingOrZero_andCapsAt64Chars() {
        assertEquals(
            "gba_pokemon_emerald_usa_rev_1",
            GameKey.compute("GBA", 0L, "Pokemon Emerald (USA) [Rev 1]"),
        )
        assertEquals(
            "nes_super_mario_bros",
            GameKey.compute("nes", null, "Super__Mario!!  Bros."),
        )
        val longName = "a".repeat(120)
        val key = GameKey.compute("snes", 0L, longName)
        assertTrue("Expected <= 64 chars, got ${key.length}", key.length <= 64)
        assertTrue(key.startsWith("snes_"))
    }

    // =========================================================================
    // 2. PKCE S256 & OAuth Loopback & Token Refresh
    // =========================================================================

    @Test
    fun pkceS256_matchesRfc7636TestVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, GoogleOAuthLoopback.computeCodeChallengeS256(verifier))
    }

    @Test
    fun oauthLoopback_fullFlowWithPreconnectFaviconWrongStateAndCodeExchange() {
        val fixedNow = 1_700_000_000_000L
        val receivedForm = AtomicReference<Map<String, String>>()
        MockDriveHttpServer().use { server ->
            server.onTokenRequest = { form ->
                receivedForm.set(form)
                200 to """{"access_token":"test-access-token-fresh","refresh_token":"test-refresh-token","expires_in":1800,"scope":"$DRIVE_SCOPE"}"""
            }

            val capturedAuthUrl = AtomicReference<String>()
            val loopback = GoogleOAuthLoopback(
                clientId = "test-desktop-client.apps.googleusercontent.com",
                clientSecret = "test-secret",
                tokenEndpoint = server.tokenEndpoint,
                clock = { fixedNow },
                openBrowser = { url ->
                    capturedAuthUrl.set(url)
                    Thread {
                        val params = parseUrlQuery(url)
                        val redirectUri = params.getValue("redirect_uri")
                        val state = params.getValue("state")
                        val port = URL(redirectUri).port

                        // 1. Idle preconnect socket that immediately closes
                        Socket("127.0.0.1", port).close()

                        // 2. /favicon.ico -> 404
                        assertEquals(404, httpGetStatus("http://127.0.0.1:$port/favicon.ico"))

                        // 3. Mismatched state -> 400
                        assertEquals(400, httpGetStatus("$redirectUri?code=bad&state=wrong-state"))

                        // 4. Valid callback -> 200
                        assertEquals(200, httpGetStatus("$redirectUri?code=auth-code-123&state=$state"))
                    }.start()
                },
            )

            val tokens = loopback.start(timeoutMs = 10_000L)
            assertEquals("test-access-token-fresh", tokens.accessToken)
            assertEquals("test-refresh-token", tokens.refreshToken)
            assertEquals(fixedNow + 1800_000L, tokens.expiresAtMs)
            assertEquals(DRIVE_SCOPE, tokens.scope)

            // Verify auth URL used single drive.file scope + S256
            val authParams = parseUrlQuery(capturedAuthUrl.get())
            assertEquals(DRIVE_SCOPE, authParams["scope"])
            assertEquals("S256", authParams["code_challenge_method"])
            assertEquals("offline", authParams["access_type"])
            assertEquals("consent", authParams["prompt"])

            // Verify token exchange sent valid PKCE verifier matching auth challenge
            val tokenForm = receivedForm.get()
            assertEquals("auth-code-123", tokenForm["code"])
            assertEquals("test-desktop-client.apps.googleusercontent.com", tokenForm["client_id"])
            assertEquals("test-secret", tokenForm["client_secret"])
            assertEquals("authorization_code", tokenForm["grant_type"])
            val verifier = tokenForm["code_verifier"]!!
            assertEquals(
                authParams["code_challenge"],
                GoogleOAuthLoopback.computeCodeChallengeS256(verifier),
            )
        }
    }

    @Test
    fun oauthTokenProvider_refreshesWithin60sSkewAndOnStaleTokenAndRotatesRefreshToken() {
        val nowMs = AtomicLong(1_000_000L)
        val refreshedRecords = mutableListOf<Triple<String, String?, Long>>()

        MockDriveHttpServer().use { server ->
            val callNum = AtomicInteger(0)
            server.onTokenRequest = { form ->
                assertEquals("refresh_token", form["grant_type"])
                val n = callNum.incrementAndGet()
                val rotated = if (n == 2) "rotated-refresh-2" else ""
                val extra = if (rotated.isNotEmpty()) ""","refresh_token":"$rotated"""" else ""
                200 to """{"access_token":"tok-$n","expires_in":3600$extra}"""
            }

            val provider = OAuthTokenProvider(
                clientId = "cid",
                clientSecret = "csec",
                initialAccessToken = "initial-tok",
                initialRefreshToken = "rt-1",
                initialExpiresAtMs = nowMs.get() + 120_000L,
                tokenEndpoint = server.tokenEndpoint,
                clock = { nowMs.get() },
                onTokensRefreshed = { a, r, e -> refreshedRecords.add(Triple(a, r, e)) },
            )

            // 120s remaining -> uses cached token without hitting server
            assertEquals("initial-tok", provider.accessToken())
            assertEquals(0, callNum.get())

            // Advance to 55s before expiry (within 60s skew) -> refreshes automatically
            nowMs.set(1_000_000L + 65_000L)
            assertEquals("tok-1", provider.accessToken())
            assertEquals(1, callNum.get())
            assertEquals(1, refreshedRecords.size)

            // Simulate HTTP 401 -> pass staleToken -> forces immediate refresh + captures rotated refresh token
            assertEquals("tok-2", provider.accessToken(staleToken = "tok-1"))
            assertEquals(2, callNum.get())
            assertEquals("rotated-refresh-2", refreshedRecords[1].second)
        }
    }

    @Test
    fun fetchDriveUserInfo_parsesDisplayNameAndEmail() {
        MockDriveHttpServer(
            validAccessToken = "about-tok",
            userEmail = "ash@kanto.example",
            userDisplayName = "Ash Ketchum",
        ).use { server ->
            val info = fetchDriveUserInfo("about-tok", apiBase = server.apiBase)
            assertEquals("ash@kanto.example", info.email)
            assertEquals("Ash Ketchum", info.displayName)
        }
    }

    // =========================================================================
    // 3. Drive REST v3 Sync Engine Tests (over real HttpGoogleDriveClient)
    // =========================================================================

    @Test
    fun syncEngine_firstUpload_createsDriveLayoutAndMetaAndBaseline() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("devA")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Pokemon Emerald (USA).gba", "gba", 0x12345678L)
            val srmBytes = "SRAM-SAVE-V1".toByteArray()
            saves.writeSaveRam(srmBytes, rom)

            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_000_000L)
            val result = engine.syncAll(listOf(rom), SyncOptions(deviceName = "Pixel 9"))

            assertEquals(1, result.uploaded)
            assertEquals(0, result.downloaded)
            assertEquals(0, result.conflicts)
            assertTrue(result.errors.isEmpty())

            // Verify Drive hierarchy: Delta Sync / games / gba_12345678 / save.srm & meta.json
            val remoteSave = server.findByPath("Delta Sync", "games", "gba_12345678", "save.srm")
            assertNotNull(remoteSave)
            assertArrayEquals(srmBytes, remoteSave!!.bytes)

            val remoteMeta = server.findByPath("Delta Sync", "games", "gba_12345678", "meta.json")
            assertNotNull(remoteMeta)
            val parsedMeta = syncJson.decodeFromString<RemoteGameMeta>(remoteMeta!!.bytes.toString(Charsets.UTF_8))
            assertEquals(1, parsedMeta.schemaVersion)
            assertEquals("gba_12345678", parsedMeta.gameKey)
            assertEquals("Pokemon Emerald", parsedMeta.gameName)
            assertEquals("gba", parsedMeta.systemId)
            assertEquals("12345678", parsedMeta.crc32)
            assertEquals("Pixel 9", parsedMeta.updatedByDevice)

            // Verify baseline file persisted
            val baseline = engine.loadBaseline()
            val entry = baseline["games/gba_12345678/save.srm"]
            assertNotNull(entry)
            assertEquals(remoteSave.id, entry!!.driveFileId)
            assertEquals(md5Hex(srmBytes), entry.localMd5)
            assertEquals(md5Hex(srmBytes), entry.remoteMd5)
        }
    }

    @Test
    fun syncEngine_remoteDownload_andIdenticalMd5SkipOnSecondDevice() = runBlocking {
        MockDriveHttpServer().use { server ->
            val srmBytes = "CLOUD-SRAM-BYTES".toByteArray()
            val rootFolder = server.seedFolder("Delta Sync")
            val gamesFolder = server.seedFolder("games", rootFolder.id)
            val gameFolder = server.seedFolder("snes_abcdef01", gamesFolder.id)
            server.seedFile(gameFolder.id, "save.srm", srmBytes, modifiedTimeMs = 1_700_000_050_000L)

            // Device B has no local save -> downloads it
            val devBRoot = tempFolder.newFolder("devB")
            val savesB = SaveFileManager(devBRoot)
            val romB = makeRom(devBRoot, "Super Metroid.sfc", "snes", 0xABCDEF01L)
            val engineB = makeEngine(server, savesB, devBRoot)

            val resDownload = engineB.syncAll(listOf(romB))
            assertEquals(0, resDownload.uploaded)
            assertEquals(1, resDownload.downloaded)
            assertArrayEquals(srmBytes, savesB.readSaveRam(romB))

            // Device C already has IDENTICAL save bytes, but NO baseline file -> MD5 skips transfer!
            val devCRoot = tempFolder.newFolder("devC")
            val savesC = SaveFileManager(devCRoot)
            val romC = makeRom(devCRoot, "Super Metroid.sfc", "snes", 0xABCDEF01L)
            savesC.writeSaveRam(srmBytes, romC)
            val engineC = makeEngine(server, savesC, devCRoot)

            val downloadsBefore = server.downloadCount.get()
            val uploadsBefore = server.uploadCount.get()
            val resSkip = engineC.syncAll(listOf(romC))

            assertEquals(0, resSkip.uploaded)
            assertEquals(0, resSkip.downloaded)
            assertEquals(1, resSkip.skipped)
            assertEquals(downloadsBefore, server.downloadCount.get())
            assertEquals(uploadsBefore, server.uploadCount.get())
        }
    }

    @Test
    fun syncEngine_conflictNewestWins_remoteNewer_createsConflictBackupAndOverwritesLocal() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("conflictNewestRemote")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Zelda.gba", "gba", 0x11223344L)
            val baseBytes = "BASE-V0".toByteArray()
            saves.writeSaveRam(baseBytes, rom)

            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_100_000L)
            assertEquals(1, engine.syncAll(listOf(rom)).uploaded)

            // Both sides diverge: local older (t=1000), remote newer (t=5000)
            val localDiverged = "LOCAL-OLDER".toByteArray()
            val remoteDiverged = "REMOTE-NEWER".toByteArray()
            saves.writeSaveRam(localDiverged, rom)
            saves.saveRamFile(rom).setLastModified(1_700_000_001_000L)

            val remoteNode = server.findByPath("Delta Sync", "games", "gba_11223344", "save.srm")!!
            remoteNode.bytes = remoteDiverged
            remoteNode.modifiedTimeMs = 1_700_000_005_000L

            val res = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.NEWEST_WINS))
            assertEquals(1, res.conflicts)
            assertEquals(1, res.downloaded)
            assertEquals(0, res.uploaded)
            assertArrayEquals(remoteDiverged, saves.readSaveRam(rom))

            // Verify `.conflict-<ts>.bak` backup exists with the overwritten local bytes
            val saveDir = saves.saveRamFile(rom).parentFile!!
            val backups = saveDir.listFiles { f -> f.name.startsWith("Zelda.srm.conflict-") && f.name.endsWith(".bak") }
            assertNotNull(backups)
            assertEquals(1, backups!!.size)
            assertArrayEquals(localDiverged, backups[0].readBytes())
        }
    }

    @Test
    fun syncEngine_conflictPreferLocal_uploadsLocalEvenWhenRemoteNewer() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("conflictPreferLocal")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Metroid.gba", "gba", 0x22334455L)
            saves.writeSaveRam("V0".toByteArray(), rom)

            val engine = makeEngine(server, saves, root)
            engine.syncAll(listOf(rom))

            val localBytes = "LOCAL-WINS".toByteArray()
            saves.writeSaveRam(localBytes, rom)
            saves.saveRamFile(rom).setLastModified(1_700_000_001_000L)

            val remoteNode = server.findByPath("Delta Sync", "games", "gba_22334455", "save.srm")!!
            remoteNode.bytes = "REMOTE-NEWER-BUT-LOSES".toByteArray()
            remoteNode.modifiedTimeMs = 1_700_000_999_000L

            val res = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.PREFER_LOCAL))
            assertEquals(1, res.conflicts)
            assertEquals(1, res.uploaded)
            assertEquals(0, res.downloaded)
            assertArrayEquals(localBytes, remoteNode.bytes)
        }
    }

    @Test
    fun syncEngine_conflictPreferCloud_backsUpAndOverwritesEvenWhenLocalNewer() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("conflictPreferCloud")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Kirby.gba", "gba", 0x33445566L)
            saves.writeSaveRam("V0".toByteArray(), rom)

            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_200_000L)
            engine.syncAll(listOf(rom))

            val localBytes = "LOCAL-NEWER-BUT-LOSES".toByteArray()
            val remoteBytes = "CLOUD-WINS".toByteArray()
            saves.writeSaveRam(localBytes, rom)
            saves.saveRamFile(rom).setLastModified(1_700_000_999_000L)

            val remoteNode = server.findByPath("Delta Sync", "games", "gba_33445566", "save.srm")!!
            remoteNode.bytes = remoteBytes
            remoteNode.modifiedTimeMs = 1_700_000_001_000L

            val res = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.PREFER_CLOUD))
            assertEquals(1, res.conflicts)
            assertEquals(1, res.downloaded)
            assertEquals(0, res.uploaded)
            assertArrayEquals(remoteBytes, saves.readSaveRam(rom))

            val backup = File(saves.saveRamFile(rom).parentFile, "Kirby.srm.conflict-1700000200000.bak")
            assertTrue("Expected conflict backup at ${backup.path}", backup.isFile)
            assertArrayEquals(localBytes, backup.readBytes())
        }
    }

    @Test
    fun syncEngine_mergesStatesIndexAndSyncsSvsAndThumbnails() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("statesMerge")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Advance Wars.gba", "gba", 0x77889900L)

            // Local has state "auto1" (older) and "gen-local"
            val header = SaveStateHeader("gba", "gba", 0x77889900L, 1000.0)
            saves.writeState(rom, "AUTO-OLD".toByteArray(), header, null, SaveStateType.AUTO, id = "auto1")
            saves.writeState(rom, "GEN-LOCAL".toByteArray(), header, "PNG-LOCAL".toByteArray(), SaveStateType.GENERAL, id = "gen-local", name = "Local Slot")
            // Pin local auto1 mtime older than remote
            saves.stateFile(rom, "auto1").setLastModified(1_700_000_001_000L)
            saves.replaceStateIndex(
                rom,
                listOf(
                    SaveStateInfo("auto1", "auto", null, false, 1000L, 1_700_000_001_000L, "gba", "gba"),
                    SaveStateInfo("gen-local", "general", "Local Slot", false, 1000L, 1_700_000_002_000L, "gba", "gba"),
                ),
            )

            // Remote has newer "auto1" + remote-only "gen-remote" with thumbnail
            val rootFolder = server.seedFolder("Delta Sync")
            val gamesFolder = server.seedFolder("games", rootFolder.id)
            val gameFolder = server.seedFolder("gba_77889900", gamesFolder.id)
            val statesFolder = server.seedFolder("states", gameFolder.id)

            val remoteAutoBytes = com.codescv.delta.saves.SaveStateContainer.encode("AUTO-NEW-REMOTE".toByteArray(), header)
            val remoteGenBytes = com.codescv.delta.saves.SaveStateContainer.encode("GEN-REMOTE".toByteArray(), header)
            val remotePngBytes = "PNG-REMOTE".toByteArray()

            server.seedFile(statesFolder.id, "auto1.svs", remoteAutoBytes, modifiedTimeMs = 1_700_000_010_000L)
            server.seedFile(statesFolder.id, "gen-remote.svs", remoteGenBytes, modifiedTimeMs = 1_700_000_010_000L)
            server.seedFile(statesFolder.id, "gen-remote.png", remotePngBytes, mimeType = "image/png", modifiedTimeMs = 1_700_000_010_000L)

            val remoteIndex = listOf(
                RemoteSaveStateEntry("auto1", "auto", null, false, 1000L, 1_700_000_010_000L, "gba", "gba"),
                RemoteSaveStateEntry("gen-remote", "general", "iOS Slot", true, 2000L, 1_700_000_010_000L, "gba", "gba"),
            )
            server.seedFile(
                gameFolder.id,
                "states_index.json",
                syncPrettyJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(RemoteSaveStateEntry.serializer()), remoteIndex)
                    .toByteArray(Charsets.UTF_8),
                mimeType = "application/json",
                modifiedTimeMs = 1_700_000_010_000L,
            )

            val engine = makeEngine(server, saves, root)
            val res = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.NEWEST_WINS))

            assertTrue(res.errors.isEmpty())
            // Uploaded: gen-local.svs + gen-local.png = 2; Downloaded: auto1.svs + gen-remote.svs + gen-remote.png = 3
            assertEquals(2, res.uploaded)
            assertEquals(3, res.downloaded)

            // Local SaveFileManager now has all 3 states with merged metadata!
            val localStates = saves.listStates(rom).associateBy { it.id }
            assertEquals(setOf("auto1", "gen-local", "gen-remote"), localStates.keys)
            assertEquals(1_700_000_010_000L, localStates["auto1"]!!.modifiedAt)
            assertEquals("iOS Slot", localStates["gen-remote"]!!.name)
            assertTrue(localStates["gen-remote"]!!.locked)
            assertArrayEquals("AUTO-NEW-REMOTE".toByteArray(), saves.readState(rom, "auto1").second)
            assertArrayEquals("GEN-REMOTE".toByteArray(), saves.readState(rom, "gen-remote").second)
            assertArrayEquals(remotePngBytes, saves.screenshotFile(rom, "gen-remote").readBytes())

            // Remote now has gen-local.svs, gen-local.png, and merged states_index.json with all 3 entries
            assertNotNull(server.findByPath("Delta Sync", "games", "gba_77889900", "states", "gen-local.svs"))
            assertNotNull(server.findByPath("Delta Sync", "games", "gba_77889900", "states", "gen-local.png"))
            val mergedRemoteIdx = server.findByPath("Delta Sync", "games", "gba_77889900", "states_index.json")!!
            val parsedRemoteIdx = syncJson.decodeFromString<List<RemoteSaveStateEntry>>(mergedRemoteIdx.bytes.toString(Charsets.UTF_8))
            assertEquals(setOf("auto1", "gen-local", "gen-remote"), parsedRemoteIdx.map { it.id }.toSet())
        }
    }

    @Test
    fun syncEngine_runningGameSafetyGuard_skipsLocalOverwritesOnlyForRunningGame() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("runningGuard")
            val saves = SaveFileManager(root)
            val runningRom = makeRom(root, "Pokemon FireRed.gba", "gba", 0xCAFEBABEL)
            val idleRom = makeRom(root, "Tetris.gb", "gb", 0x10203040L)

            val localRunningBytes = "IN-PROGRESS-LOCAL".toByteArray()
            saves.writeSaveRam(localRunningBytes, runningRom)
            saves.saveRamFile(runningRom).setLastModified(1_700_000_001_000L)

            val rootFolder = server.seedFolder("Delta Sync")
            val gamesFolder = server.seedFolder("games", rootFolder.id)
            val runningFolder = server.seedFolder("gba_cafebabe", gamesFolder.id)
            server.seedFile(runningFolder.id, "save.srm", "CLOUD-OVERWRITE".toByteArray(), modifiedTimeMs = 1_700_000_099_000L)

            val idleFolder = server.seedFolder("gb_10203040", gamesFolder.id)
            val idleCloudBytes = "TETRIS-CLOUD".toByteArray()
            server.seedFile(idleFolder.id, "save.srm", idleCloudBytes, modifiedTimeMs = 1_700_000_099_000L)

            val engine = makeEngine(
                server = server,
                saves = saves,
                root = root,
                isGameRunning = { it.id == runningRom.id },
            )
            val res = engine.syncAll(listOf(runningRom, idleRom), SyncOptions(conflictPolicy = ConflictPolicy.NEWEST_WINS))

            assertEquals(1, res.skippedRunningGames)
            assertEquals(1, res.downloaded)
            // Running game's local save MUST remain untouched
            assertArrayEquals(localRunningBytes, saves.readSaveRam(runningRom))
            // Idle game synced normally
            assertArrayEquals(idleCloudBytes, saves.readSaveRam(idleRom))
        }
    }

    @Test
    fun httpDriveClient_retriesOnceOnHttp401AfterRefreshingToken() = runBlocking {
        MockDriveHttpServer(validAccessToken = "server-expected-new-token").use { server ->
            val provider = OAuthTokenProvider(
                clientId = "cid",
                initialAccessToken = "expired-token",
                initialRefreshToken = "valid-rt",
                initialExpiresAtMs = System.currentTimeMillis() + 600_000L, // thinks it's still valid!
                tokenEndpoint = server.tokenEndpoint,
            )
            val client = HttpGoogleDriveClient(
                tokenProvider = provider,
                apiBase = server.apiBase,
                uploadBase = server.uploadBase,
                baseBackoffMs = 10L,
                maxJitterMs = 0L,
            )

            // First call sends "expired-token" -> 401 -> refreshes via mock token endpoint -> retries and succeeds!
            val folder = client.findOrCreateFolder("Delta Sync")
            assertEquals("Delta Sync", folder.name)
            assertEquals(1, server.refreshCount.get())
        }
    }

    @Test
    fun syncManager_singleFlightCoalescesOverlappingSyncs() = runBlocking {
        val root = tempFolder.newFolder("singleFlight")
        val saves = SaveFileManager(root)
        val prefs = PreferencesStore(File(root, "preferences.json"))
        val secretStore = InMemorySyncSecretStore(
            StoredOAuthCredentials(
                clientId = "cid",
                accessToken = "tok",
                refreshToken = "rt",
                expiresAtMs = Long.MAX_VALUE / 2,
                accountEmail = "u@example.com",
            ),
        )

        val invocations = AtomicInteger(0)
        val entered = CompletableDeferred<Unit>()
        val fakeDrive = object : DriveApi {
            override suspend fun findOrCreateFolder(name: String, parentId: String?): DriveFile {
                if (name == DELTA_SYNC_ROOT_FOLDER) {
                    invocations.incrementAndGet()
                    entered.complete(Unit)
                    delay(150)
                }
                return DriveFile(id = "id_$name", name = name, mimeType = DRIVE_FOLDER_MIME)
            }

            override suspend fun listChildren(folderId: String): List<DriveFile> = emptyList()
            override suspend fun downloadFile(fileId: String): ByteArray = ByteArray(0)
            override suspend fun uploadFile(
                parentId: String,
                name: String,
                mimeType: String,
                content: ByteArray,
                existingFileId: String?,
                modifiedTimeMs: Long?,
            ): DriveFile = DriveFile(id = "f1", name = name, mimeType = mimeType)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = GoogleDriveSyncManager(
                secretStore = secretStore,
                preferences = prefs,
                romsProvider = { emptyList() },
                saves = saves,
                filesDir = root,
                scope = scope,
                driveApiFactory = { fakeDrive },
            )

            val job1 = async(Dispatchers.IO) { manager.syncNow() }
            withTimeout(2_000L) { entered.await() }
            val job2 = async(Dispatchers.IO) { manager.syncNow() }

            val results = awaitAll(job1, job2)
            assertEquals(1, invocations.get())
            assertEquals(results[0], results[1])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syncEngine_fastForwardAndNewestWinsLocalNewer_behavesCorrectly() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("ffAndLocalWins")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Golden Sun.gba", "gba", 0x55667788L)
            saves.writeSaveRam("V1".toByteArray(), rom)

            val engine = makeEngine(server, saves, root, clockMs = 1_700_000_000_000L)
            assertEquals(1, engine.syncAll(listOf(rom)).uploaded)

            // Fast-forward upload: only local changes
            saves.writeSaveRam("V2-LOCAL".toByteArray(), rom)
            val ffUp = engine.syncAll(listOf(rom))
            assertEquals(1, ffUp.uploaded)
            assertEquals(0, ffUp.conflicts)

            // Fast-forward download: only remote changes
            val remoteNode = server.findByPath("Delta Sync", "games", "gba_55667788", "save.srm")!!
            remoteNode.bytes = "V3-REMOTE".toByteArray()
            remoteNode.modifiedTimeMs = 1_700_000_020_000L
            val ffDown = engine.syncAll(listOf(rom))
            assertEquals(1, ffDown.downloaded)
            assertEquals(0, ffDown.conflicts)
            assertArrayEquals("V3-REMOTE".toByteArray(), saves.readSaveRam(rom))

            // Both diverge, local newer -> NEWEST_WINS uploads local with no .bak created
            val localNewer = "V4-LOCAL-NEWER".toByteArray()
            saves.writeSaveRam(localNewer, rom)
            saves.saveRamFile(rom).setLastModified(1_700_000_090_000L)
            remoteNode.bytes = "V4-REMOTE-OLDER".toByteArray()
            remoteNode.modifiedTimeMs = 1_700_000_030_000L

            val conflictRes = engine.syncAll(listOf(rom), SyncOptions(conflictPolicy = ConflictPolicy.NEWEST_WINS))
            assertEquals(1, conflictRes.conflicts)
            assertEquals(1, conflictRes.uploaded)
            assertEquals(0, conflictRes.downloaded)
            assertArrayEquals(localNewer, remoteNode.bytes)
            val backups = saves.saveRamFile(rom).parentFile!!.listFiles { f -> f.name.endsWith(".bak") }
            assertEquals(0, backups?.size ?: 0)
        }
    }

    @Test
    fun httpDriveClient_resumableUpload_uploadsAndUpdatesPayloadAboveThreshold() = runBlocking {
        MockDriveHttpServer().use { server ->
            val client = HttpGoogleDriveClient(
                tokenProvider = DriveTokenProvider { server.validAccessToken },
                apiBase = server.apiBase,
                uploadBase = server.uploadBase,
                multipartThresholdBytes = 16,
                baseBackoffMs = 10L,
                maxJitterMs = 0L,
            )
            val root = client.findOrCreateFolder("Delta Sync")
            val payload = ByteArray(128) { (it * 7).toByte() }
            val created = client.uploadFile(
                parentId = root.id,
                name = "big.svs",
                mimeType = "application/octet-stream",
                content = payload,
            )
            assertEquals(md5Hex(payload), created.md5Checksum)
            assertArrayEquals(payload, client.downloadFile(created.id))

            val payload2 = ByteArray(256) { (it * 13).toByte() }
            val updated = client.uploadFile(
                parentId = root.id,
                name = "big.svs",
                mimeType = "application/octet-stream",
                content = payload2,
                existingFileId = created.id,
            )
            assertEquals(created.id, updated.id)
            assertEquals(md5Hex(payload2), updated.md5Checksum)
            assertArrayEquals(payload2, client.downloadFile(created.id))
        }
    }

    @Test
    fun oauthLoopback_deniedAuthorization_throwsGDriveAuthException() {
        val loopback = GoogleOAuthLoopback(
            clientId = "test-client",
            openBrowser = { url ->
                Thread {
                    val params = parseUrlQuery(url)
                    val redirectUri = params.getValue("redirect_uri")
                    val state = params.getValue("state")
                    assertEquals(200, httpGetStatus("$redirectUri?error=access_denied&state=$state"))
                }.start()
            },
        )
        try {
            loopback.start(timeoutMs = 5_000L)
            fail("Expected GDriveAuthException")
        } catch (e: GDriveAuthException) {
            assertTrue(e.needsInteraction)
        }
    }

    @Test
    fun syncManager_autoSyncDebounce_respects30SecondWindow() = runBlocking {
        val root = tempFolder.newFolder("debounce")
        val saves = SaveFileManager(root)
        val prefs = PreferencesStore(File(root, "preferences.json"))
        val secretStore = InMemorySyncSecretStore(
            StoredOAuthCredentials(
                clientId = "cid",
                accessToken = "tok",
                refreshToken = "rt",
                expiresAtMs = Long.MAX_VALUE / 2,
                accountEmail = "u@example.com",
            ),
        )
        val nowMs = AtomicLong(100_000L)
        val calls = AtomicInteger(0)
        val firstDone = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        val fakeDrive = object : DriveApi {
            override suspend fun findOrCreateFolder(name: String, parentId: String?): DriveFile {
                if (name == DELTA_SYNC_ROOT_FOLDER) {
                    val n = calls.incrementAndGet()
                    if (n == 1) firstDone.complete(Unit)
                    if (n == 2) secondDone.complete(Unit)
                }
                return DriveFile(id = "id_$name", name = name, mimeType = DRIVE_FOLDER_MIME)
            }
            override suspend fun listChildren(folderId: String): List<DriveFile> = emptyList()
            override suspend fun downloadFile(fileId: String): ByteArray = ByteArray(0)
            override suspend fun uploadFile(
                parentId: String, name: String, mimeType: String, content: ByteArray,
                existingFileId: String?, modifiedTimeMs: Long?,
            ): DriveFile = DriveFile(id = "f1", name = name, mimeType = mimeType)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = GoogleDriveSyncManager(
                secretStore = secretStore,
                preferences = prefs,
                romsProvider = { emptyList() },
                saves = saves,
                filesDir = root,
                scope = scope,
                clock = { nowMs.get() },
                driveApiFactory = { fakeDrive },
            )

            manager.triggerAutoSync("resume", debounce = true)
            withTimeout(2_000L) { firstDone.await() }
            delay(50)
            assertEquals(1, calls.get())

            // 10 seconds later -> debounced!
            nowMs.addAndGet(10_000L)
            manager.triggerAutoSync("resume", debounce = true)
            delay(100)
            assertEquals(1, calls.get())

            // 31 seconds after first sync -> fires again!
            nowMs.addAndGet(21_000L)
            manager.triggerAutoSync("resume", debounce = true)
            withTimeout(2_000L) { secondDone.await() }
            assertEquals(2, calls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun syncEngine_corruptRemoteStatesIndex_recordsErrorAndDoesNotOverwriteRemote() = runBlocking {
        MockDriveHttpServer().use { server ->
            val root = tempFolder.newFolder("corruptIndex")
            val saves = SaveFileManager(root)
            val rom = makeRom(root, "Castlevania.gba", "gba", 0x99887766L)
            val header = SaveStateHeader("gba", "gba", 0x99887766L, 1000.0)
            saves.writeState(rom, "LOCAL".toByteArray(), header, null, SaveStateType.GENERAL, id = "local1", name = "Local")

            val r = server.seedFolder("Delta Sync")
            val g = server.seedFolder("games", r.id)
            val gameFolder = server.seedFolder("gba_99887766", g.id)
            val corruptBytes = "{not-a-json-array".toByteArray()
            server.seedFile(gameFolder.id, "states_index.json", corruptBytes, "application/json", 1_700_000_010_000L)

            val res = makeEngine(server, saves, root).syncAll(listOf(rom))
            assertTrue("Expected per-game error for corrupt states_index.json", res.errors.isNotEmpty())
            val remoteIdx = server.findByPath("Delta Sync", "games", "gba_99887766", "states_index.json")!!
            assertArrayEquals(corruptBytes, remoteIdx.bytes)
        }
    }

    @Test
    fun syncManager_signOutDuringInFlightSync_doesNotResurrectBaselineOrTokens() = runBlocking {
        val root = tempFolder.newFolder("signOutRace")
        val saves = SaveFileManager(root)
        val prefs = PreferencesStore(File(root, "preferences.json"))
        val secretStore = InMemorySyncSecretStore(
            StoredOAuthCredentials(
                clientId = "cid",
                accessToken = "tok",
                refreshToken = "rt",
                expiresAtMs = Long.MAX_VALUE / 2,
                accountEmail = "u@example.com",
            ),
        )
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val fakeDrive = object : DriveApi {
            override suspend fun findOrCreateFolder(name: String, parentId: String?): DriveFile {
                if (name == DELTA_SYNC_ROOT_FOLDER) {
                    entered.complete(Unit)
                    proceed.await()
                }
                return DriveFile(id = "id_$name", name = name, mimeType = DRIVE_FOLDER_MIME)
            }
            override suspend fun listChildren(folderId: String): List<DriveFile> = emptyList()
            override suspend fun downloadFile(fileId: String): ByteArray = ByteArray(0)
            override suspend fun uploadFile(
                parentId: String, name: String, mimeType: String, content: ByteArray,
                existingFileId: String?, modifiedTimeMs: Long?,
            ): DriveFile = DriveFile(id = "f1", name = name, mimeType = mimeType)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val manager = GoogleDriveSyncManager(
                secretStore = secretStore,
                preferences = prefs,
                romsProvider = { emptyList() },
                saves = saves,
                filesDir = root,
                scope = scope,
                driveApiFactory = { fakeDrive },
            )
            val syncJob = async(Dispatchers.IO) { runCatching { manager.syncNow() } }
            withTimeout(2_000L) { entered.await() }
            manager.signOut()
            proceed.complete(Unit)
            syncJob.await()

            assertFalse(manager.uiState.value.isSignedIn)
            assertFalse(secretStore.load().isSignedIn)
            assertFalse("Baseline file should not be re-created after signOut", File(root, SYNC_STATE_FILE_NAME).exists())
        } finally {
            scope.cancel()
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeRom(root: File, fileName: String, systemId: String, crc32: Long?): RomEntry {
        val romFile = File(File(root, "ROMs").apply { mkdirs() }, fileName)
        romFile.writeBytes(byteArrayOf(1, 2, 3, 4))
        return RomEntry(
            path = romFile.absolutePath,
            systemId = systemId,
            fileSize = romFile.length(),
            crc32 = crc32,
        )
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

    private fun parseUrlQuery(url: String): Map<String, String> {
        val q = url.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').associate { pair ->
            val k = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val v = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            k to v
        }
    }

    private fun httpGetStatus(url: String): Int {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = 3_000
        }
        return try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}

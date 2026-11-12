package com.codescv.delta.ui.library

import com.codescv.delta.library.CoverArtFetcher
import com.codescv.delta.library.LibraryState
import com.codescv.delta.library.LibraryStore
import com.codescv.delta.library.OpenVgdbTable
import com.codescv.delta.library.RecentGame
import com.codescv.delta.library.RomEntry
import com.codescv.delta.model.GameSystem
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.saves.SaveStateInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LibraryLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun rom(name: String, system: GameSystem) = RomEntry("/roms/$name", system.id, 1024, null)

    private val advanceWars = rom("Advance Wars (USA) (Rev 1).gba", GameSystem.GBA)
    private val zelda = rom("Legend of Zelda, The - Link's Awakening DX (USA).gbc", GameSystem.GBC)
    private val tetris = rom("Tetris (World).gb", GameSystem.GB)
    private val mario = rom("Super Mario Bros. (World).nes", GameSystem.NES)
    private val sonic = rom("Sonic the Hedgehog (USA, Europe).md", GameSystem.GENESIS)
    private val gg = rom("Columns (World).gg", GameSystem.GAME_GEAR)

    // Pages ------------------------------------------------------------------------------------

    @Test
    fun emptyLibraryShowsSevenEmptySystemPagesWithoutFavorites() {
        val pages = LibraryLogic.pages(LibraryState().availableSystems)
        assertEquals(7, pages.size)
        assertTrue(pages.none { it is LibraryPage.Favorites })
        assertEquals(
            listOf("Nintendo", "Super Nintendo", "Nintendo 64", "Game Boy Color", "Game Boy Advance", "Nintendo DS", "Sega Genesis"),
            pages.map { it.title },
        )
    }

    @Test
    fun pagesAreFavoritesPlusAvailableSystemsInDeltaOrder() {
        val state = LibraryState(entries = listOf(advanceWars, zelda, tetris, mario, sonic, gg))
        val pages = LibraryLogic.pages(state.availableSystems)
        assertEquals(listOf("favorites", "nes", "gb", "gba", "md"), pages.map { it.id })
        assertEquals(listOf("Favorites", "Nintendo", "Game Boy Color", "Game Boy Advance", "Sega Genesis"), pages.map { it.title })
    }

    @Test
    fun gbAndGbcShareAPageAndGameGearFoldsIntoGenesis() {
        val state = LibraryState(entries = listOf(zelda, tetris, sonic, gg))
        val gbPage = LibraryPage.System(GameSystem.GB)
        assertEquals(setOf(zelda, tetris), LibraryLogic.entries(gbPage, state).toSet())
        assertEquals(setOf(sonic, gg), LibraryLogic.entries(LibraryPage.System(GameSystem.GENESIS), state).toSet())
    }

    @Test
    fun currentPageDefaultsToFirstSystemNeverFavorites() {
        val pages = LibraryLogic.pages(listOf(GameSystem.GBA, GameSystem.NDS))
        assertEquals(LibraryPage.System(GameSystem.GBA), LibraryLogic.currentPage(pages, null))
        assertEquals(LibraryPage.Favorites, LibraryLogic.currentPage(pages, "favorites"))
        assertEquals(LibraryPage.System(GameSystem.NDS), LibraryLogic.currentPage(pages, "nds"))
        // A page that disappeared (e.g. last NES game deleted) falls back to the first system page.
        assertEquals(LibraryPage.System(GameSystem.GBA), LibraryLogic.currentPage(pages, "nes"))
        assertNull(LibraryLogic.currentPage(emptyList(), "gba"))
    }

    @Test
    fun favoritesPageListsFavoriteEntries() {
        val state = LibraryState(entries = listOf(advanceWars, mario), favorites = setOf(mario.id))
        assertEquals(listOf(mario), LibraryLogic.entries(LibraryPage.Favorites, state))
    }

    @Test
    fun topTitleAndFloatingControls() {
        val pages = LibraryLogic.pages(listOf(GameSystem.GBA))
        assertEquals("Game Boy Advance", LibraryLogic.topTitle("", pages[1]))
        assertEquals("Search", LibraryLogic.topTitle("  war ", pages[1]))
        assertEquals("Delta", LibraryLogic.topTitle("", null))
        assertEquals("Delta", LibraryLogic.topTitle("   ", null))
        assertTrue(LibraryLogic.showPageSwitcher(pages, ""))
        assertFalse(LibraryLogic.showPageSwitcher(pages, "x"))
        assertFalse(LibraryLogic.showPageSwitcher(pages.take(1), ""))
    }

    @Test
    fun controllerIconsFollowCategory() {
        assertEquals("DeltaAssets/ControllerIcon_GBC.pdf", LibraryLogic.controllerIconAsset(GameSystem.GB))
        assertEquals("DeltaAssets/ControllerIcon_GBC.pdf", LibraryLogic.controllerIconAsset(GameSystem.GBC))
        assertEquals("DeltaAssets/ControllerIcon_DS.pdf", LibraryLogic.controllerIconAsset(GameSystem.NDS))
        assertEquals("DeltaAssets/ControllerIcon_Genesis.pdf", LibraryLogic.controllerIconAsset(GameSystem.GAME_GEAR))
        assertNull(LibraryLogic.controllerIconAsset(GameSystem.CHIP8))
        for (s in LibraryLogic.deltaSystems) {
            val asset = LibraryLogic.controllerIconAsset(s)!!
            assertTrue("$asset bundled", File(com.codescv.delta.TestAssets.assetsDir, asset).isFile)
        }
    }

    // Search -----------------------------------------------------------------------------------

    @Test
    fun searchMatchesTitleFileNameOrExactShortName() {
        val state = LibraryState(entries = listOf(advanceWars, zelda, tetris, mario, sonic))
        assertEquals(listOf(advanceWars), LibraryLogic.searchResults(state, "  ADVANCE "))
        assertEquals(listOf(advanceWars), LibraryLogic.searchResults(state, "rev 1"))
        assertEquals(listOf(advanceWars), LibraryLogic.searchResults(state, "gba"))
        assertEquals(listOf(sonic), LibraryLogic.searchResults(state, "md"))
        assertTrue(LibraryLogic.searchResults(state, "metroid").isEmpty())
        assertEquals("No games match “metroid”.", LibraryLogic.noResultsMessage("metroid"))
        assertFalse(LibraryLogic.isSearching("   "))
        assertTrue(LibraryLogic.isSearching(" a "))
    }

    @Test
    fun continuePlayingIsRecentFirstCappedAtTwelveAndHiddenWhileSearching() {
        val roms = (1..15).map { rom("Game $it.gba", GameSystem.GBA) }
        val recents = roms.mapIndexed { i, r -> RecentGame(r.id, 1000L + i) }
        val state = LibraryState(entries = roms, recents = recents)
        val cont = LibraryLogic.continuePlaying(state)
        assertEquals(12, cont.size)
        assertEquals(roms.last(), cont.first())
        assertTrue(LibraryLogic.showContinuePlaying(state, ""))
        assertFalse(LibraryLogic.showContinuePlaying(state, "game"))
        assertFalse(LibraryLogic.showContinuePlaying(LibraryState(entries = roms), ""))
    }

    @Test
    fun emptyStateStringsAreVerbatim() {
        assertEquals("Long-press a game and choose Add to Favorites.", LibraryLogic.emptySubtitle(LibraryPage.Favorites))
        assertEquals(
            "Tap + to import .zip, .gba, .gb, .gbc, .nes, .sfc, .n64, .nds or .md games.",
            LibraryLogic.emptySubtitle(LibraryPage.System(GameSystem.GBA)),
        )
        assertEquals("Imported 3 game(s)", LibraryLogic.importedToast(3))
    }

    // Play / resume ----------------------------------------------------------------------------

    @Test
    fun tapResumesAutoSaveOnlyWhenAutoResumeIsOnAndOneExists() {
        assertEquals("auto2", LibraryLogic.resumeStateForTap(autoResume = true, latestAutoStateId = "auto2"))
        assertNull(LibraryLogic.resumeStateForTap(autoResume = false, latestAutoStateId = "auto2"))
        assertNull(LibraryLogic.resumeStateForTap(autoResume = true, latestAutoStateId = null))
        assertEquals("auto1", LibraryLogic.resumeStateForContinue("auto1"))
        assertNull(LibraryLogic.resumeStateForContinue(null))
    }

    @Test
    fun latestAutoStateDrivesResumeDecision() {
        val saves = SaveFileManager(tmp.root)
        val game = RomEntry(File(tmp.root, "ROMs/Advance Wars.gba").absolutePath, GameSystem.GBA.id, 4, 0x26FD0FC9L)
        val header = com.codescv.delta.saves.SaveStateHeader("gba", "gba", 0x26FD0FC9L, 0.0)
        assertNull(LibraryLogic.resumeStateForTap(true, saves.latestAutoState(game)?.id))
        val first = saves.writeState(game, byteArrayOf(1), header, null, com.codescv.delta.saves.SaveStateType.AUTO)
        Thread.sleep(5)
        saves.writeState(game, byteArrayOf(2), header, null, com.codescv.delta.saves.SaveStateType.QUICK)
        assertEquals(first.id, LibraryLogic.resumeStateForTap(true, saves.latestAutoState(game)?.id))
        assertNull(LibraryLogic.resumeStateForTap(false, saves.latestAutoState(game)?.id))
    }

    // Context menu -----------------------------------------------------------------------------

    @Test
    fun contextMenuOrderMatchesDelta() {
        val info = RomMenuInfo("auto1", hasSaveRam = true, latestState = stateInfo("auto1"))
        val titles = gameMenuEntries(isFavorite = false, info = info).map { it.title }
        assertEquals(
            listOf(
                "Play", "Resume Auto Save", "Add to Favorites", "Rename…",
                "Fetch Cover Artwork", "Change Cover Artwork…", "Reset Cover Artwork",
                "Import Save File (.sav/.srm)…", "Export Save File (.sav)…",
                "Import Save State…", "Export Save State…", "View Save States…",
                "Share ROM…", "Delete",
            ),
            titles,
        )
        val sections = gameMenuEntries(false, info).mapNotNull { it.section }
        assertEquals(listOf("Cover Artwork", "Game Save (.sav / .srm)", "Save States (.svs / .state)"), sections)
    }

    @Test
    fun contextMenuHidesResumeAndDisablesExportsWithoutData() {
        val entries = gameMenuEntries(isFavorite = true, info = RomMenuInfo(null, hasSaveRam = false, latestState = null))
        assertTrue(entries.none { it.action == GameMenuAction.RESUME_AUTO_SAVE })
        assertEquals("Remove from Favorites", entries.first { it.action == GameMenuAction.TOGGLE_FAVORITE }.title)
        assertFalse(entries.first { it.action == GameMenuAction.EXPORT_SAVE }.enabled)
        assertFalse(entries.first { it.action == GameMenuAction.EXPORT_STATE }.enabled)
        assertTrue(entries.first { it.action == GameMenuAction.IMPORT_STATE }.enabled)
    }

    private fun stateInfo(id: String) = SaveStateInfo(id, SaveStateInfo.KIND_AUTO, null, false, 1, 1, "gba", "gba")

    // Cover fetching ---------------------------------------------------------------------------

    @Test
    fun fetchCoversRunsAtMostFourConcurrentlyAndBumpsRevision() = runBlocking {
        val inFlight = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val png = ByteArray(2048).also {
            it[0] = 0x89.toByte(); it[1] = 'P'.code.toByte(); it[2] = 'N'.code.toByte(); it[3] = 'G'.code.toByte()
        }
        val fetcher = CoverArtFetcher(File(tmp.root, "Covers"), { OpenVgdbTable.EMPTY }) { _ ->
            val now = inFlight.incrementAndGet()
            peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            Thread.sleep(30)
            inFlight.decrementAndGet()
            200 to png
        }
        val store = LibraryStore(tmp.root, File(tmp.root, "library.json"), SaveFileManager(tmp.root), fetcher)
        val roms = (1..10).map { RomEntry(File(tmp.root, "ROMs/Game $it.gba").absolutePath, GameSystem.GBA.id, 4, null) }
        val before = store.value.coverRevision
        val stored = store.fetchCovers(roms)
        assertEquals(10, stored)
        assertTrue("peak ${peak.get()}", peak.get() in 2..LibraryStore.COVER_FETCH_PARALLELISM)
        assertEquals(before + 10, store.value.coverRevision)
        roms.forEach { assertTrue(fetcher.coverIfExists(it) != null) }
        // Second pass: everything cached, nothing stored, no network.
        assertEquals(0, store.fetchCovers(roms))
    }

    @Test
    fun customCoverAndResetBumpRevision() {
        val fetcher = CoverArtFetcher(File(tmp.root, "Covers"), { OpenVgdbTable.EMPTY }) { _ -> 404 to ByteArray(0) }
        val store = LibraryStore(tmp.root, File(tmp.root, "library.json"), SaveFileManager(tmp.root), fetcher)
        val game = RomEntry(File(tmp.root, "ROMs/Game.gba").absolutePath, GameSystem.GBA.id, 4, null)
        val r0 = store.value.coverRevision
        store.storeCustomCover(byteArrayOf(1, 2, 3), game)
        assertTrue(fetcher.coverIfExists(game) != null)
        assertEquals(r0 + 1, store.value.coverRevision)
        store.resetCover(game)
        assertNull(fetcher.coverIfExists(game))
        assertEquals(r0 + 2, store.value.coverRevision)
    }

    @Test
    fun hasRomFileComparesNameAndSize() {
        val fetcher = CoverArtFetcher(File(tmp.root, "Covers"), { OpenVgdbTable.EMPTY }) { _ -> 404 to ByteArray(0) }
        val store = LibraryStore(tmp.root, File(tmp.root, "library.json"), SaveFileManager(tmp.root), fetcher)
        File(store.romsDirectory, "Game.gba").writeBytes(ByteArray(8))
        assertTrue(store.hasRomFile("Game.gba", 8))
        assertTrue(store.hasRomFile("sub/Game.gba", 8))
        assertFalse(store.hasRomFile("Game.gba", 9))
        assertFalse(store.hasRomFile("Other.gba", 8))
        assertTrue(LinkedFolderSync.isImportable("Pack.ZIP"))
        assertTrue(LinkedFolderSync.isImportable("Game.sfc"))
        assertFalse(LinkedFolderSync.isImportable("readme.txt"))
    }

    @Test
    fun noForbiddenStringsInLibraryUi() {
        val dir = listOf("src/main/java/com/codescv/delta/ui/library", "app/src/main/java/com/codescv/delta/ui/library")
            .map(::File).first { it.isDirectory }
        val forbidden = Regex("""Patreon|Patrons|Unlocked|\bFree\b""")
        dir.listFiles()!!.filter { it.extension == "kt" }.forEach { f ->
            assertFalse("${f.name} contains a forbidden string", forbidden.containsMatchIn(f.readText()))
        }
    }
}

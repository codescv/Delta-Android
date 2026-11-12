package com.codescv.delta.library

import com.codescv.delta.TestAssets
import com.codescv.delta.model.GameSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val table by lazy { OpenVgdbTable.parse(TestAssets.text("OpenVGDB/openvgdb_boxart.tsv")) }

    private fun rom(name: String, system: GameSystem, crc: Long? = null) =
        RomEntry(File(tmp.root, name).absolutePath, system.id, 1024, crc)

    @Test
    fun openVgdbTableLoadsAllMappings() {
        assertTrue("sections ${table.sectionNames}", table.sectionNames.containsAll(listOf("GBA", "NES", "SNES")))
        // The bundled export ships 16,807 CRC32 -> GameFAQs mappings.
        val dataLines = TestAssets.text("OpenVGDB/openvgdb_boxart.tsv").lines().count { it.isNotEmpty() && it[0] != '#' && it[0] != '@' }
        assertTrue("entries ${table.totalEntries}", table.totalEntries >= 16_000)
        assertTrue(table.totalEntries <= dataLines)
    }

    @Test
    fun advanceWarsCrcMapsToGameFaqsBox13036() {
        val ids = table.lookup("GBA", 0x26FD0FC9L)
        assertEquals(listOf("13036"), ids)
        val urls = CoverArtFetcher.openVgdbCoverUrls(0x26FD0FC9L, GameSystem.GBA, table)
        assertEquals(listOf("https://gamefaqs.gamespot.com/a/box/0/3/6/13036_front.jpg"), urls)
    }

    @Test
    fun gameFaqsUrlPadsShortIds() {
        assertEquals("https://gamefaqs.gamespot.com/a/box/0/0/7/7_front.jpg", CoverArtFetcher.gameFaqsCoverUrl("7"))
        assertNull(CoverArtFetcher.gameFaqsCoverUrl("12a"))
        assertNull(CoverArtFetcher.gameFaqsCoverUrl(""))
    }

    @Test
    fun parseHandlesCommentsSectionsAndMultipleIds() {
        val t = OpenVgdbTable.parse("# comment\n@GBA\nDEADBEEF\t1,2\n@NES\n0000000A\t99\nbad line\n")
        assertEquals(listOf("1", "2"), t.lookup("GBA", 0xDEADBEEFL))
        assertEquals(listOf("99"), t.lookup("NES", 10L))
        assertNull(t.lookup("GBA", 10L))
    }

    @Test
    fun candidateUrlsOrderOpenVgdbThenLibretroMirrors() {
        val r = rom("Advance Wars (USA) (Rev 1).gba", GameSystem.GBA, 0x26FD0FC9L)
        val urls = CoverArtFetcher.candidateUrls(r, table)
        assertEquals("https://gamefaqs.gamespot.com/a/box/0/3/6/13036_front.jpg", urls[0])
        assertEquals(
            "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/Advance%20Wars%20(USA)%20(Rev%201).png",
            urls[1],
        )
        assertEquals(
            "https://cdn.jsdelivr.net/gh/libretro-thumbnails/Nintendo_-_Game_Boy_Advance@master/Named_Boxarts/Advance%20Wars%20(USA)%20(Rev%201).png",
            urls[2],
        )
        assertTrue(urls[3].startsWith("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/"))
    }

    @Test
    fun candidateFileNamesStripRevisionAndAddRegions() {
        val names = CoverArtFetcher.candidateFileNames(rom("Advance Wars (USA) (Rev 1).gba", GameSystem.GBA))
        assertEquals("Advance Wars (USA) (Rev 1)", names[0])
        assertEquals("Advance Wars (USA)", names[1])
        assertTrue("Advance Wars (USA, Europe)" in names)
        assertTrue("Advance Wars" in names)
        val poke = CoverArtFetcher.candidateFileNames(rom("Pokemon Emerald.gba", GameSystem.GBA))
        assertTrue("Pokemon - Emerald Version (USA, Europe)" in poke)
        assertEquals("a_b_c", CoverArtFetcher.sanitized("a&b:c"))
    }

    @Test
    fun fetcherFallsBackFromGameFaqs403ToLibretro() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(2000)
        val requested = mutableListOf<String>()
        val fetcher = CoverArtFetcher(File(tmp.root, "Covers"), { table }) { url ->
            requested += url
            when {
                "gamefaqs" in url -> 403 to ByteArray(0)
                "thumbnails.libretro.com" in url -> 200 to png
                else -> 404 to ByteArray(0)
            }
        }
        val r = rom("Advance Wars (USA) (Rev 1).gba", GameSystem.GBA, 0x26FD0FC9L)
        val file = fetcher.fetchCoverIfNeeded(r)!!
        assertTrue(file.isFile)
        assertEquals(2, requested.size)
        assertTrue("gamefaqs" in requested[0])
        assertEquals(file, fetcher.coverIfExists(r))
        // Already cached: no new requests.
        fetcher.fetchCoverIfNeeded(r)
        assertEquals(2, requested.size)
    }

    @Test
    fun fetcherRecordsMissOnlyWhenServerAnswered() {
        var calls = 0
        val fetcher = CoverArtFetcher(File(tmp.root, "Covers"), { OpenVgdbTable.EMPTY }) { _ ->
            calls++
            404 to ByteArray(0)
        }
        val r = rom("Unknown Homebrew.gba", GameSystem.GBA)
        assertNull(fetcher.fetchCoverIfNeeded(r))
        val first = calls
        assertNull(fetcher.fetchCoverIfNeeded(r))
        assertEquals("miss marker suppresses retries", first, calls)
        // Offline: no marker.
        val offline = CoverArtFetcher(File(tmp.root, "Covers2"), { OpenVgdbTable.EMPTY }) { _ -> throw java.io.IOException("offline") }
        assertNull(offline.fetchCoverIfNeeded(r))
        assertFalse(File(tmp.root, "Covers2/gba/Unknown Homebrew.missing").exists())
    }

    @Test
    fun imageMagicDetection() {
        assertTrue(CoverArtFetcher.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(20)))
        assertFalse(CoverArtFetcher.looksLikeImage("<html>blocked</html>".toByteArray()))
    }

    @Test
    fun cleanTitle() {
        assertEquals("Advance Wars", RomScanner.cleanTitle("Advance Wars (USA) (Rev 1)"))
        assertEquals("Super Mario World", RomScanner.cleanTitle("Super_Mario_World [!]"))
        assertEquals("(USA)", RomScanner.cleanTitle("(USA)"))
    }

    @Test
    fun crc32MatchesJavaUtilZip() {
        val data = "The quick brown fox jumps over the lazy dog".toByteArray()
        assertEquals(0x414FA339L, RomScanner.crc32(data))
        val f = tmp.newFile("x.gba").apply { writeBytes(data) }
        assertEquals(0x414FA339L, RomScanner.crc32(f))
        val entry = RomScanner.makeEntry(f)!!
        assertEquals("414FA339", entry.crcString)
        assertEquals(GameSystem.GBA, entry.system)
    }

    @Test
    fun headerlessNesCrcSkipsInesHeader() {
        val body = ByteArray(32 * 1024) { (it % 251).toByte() }
        val header = byteArrayOf(0x4E, 0x45, 0x53, 0x1A) + ByteArray(12)
        val f = tmp.newFile("game.nes").apply { writeBytes(header + body) }
        assertEquals(CRC32().apply { update(body) }.value, RomScanner.headerlessNesCrc(f))
        val g = tmp.newFile("plain.nes").apply { writeBytes(body) }
        assertNull(RomScanner.headerlessNesCrc(g))
    }

    private fun makeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun zipExtractionPreservesInnerNameAndSkipsJunk() {
        val rom = ByteArray(4096) { it.toByte() }
        val zip = File(tmp.root, "renamed_download.zip")
        makeZip(
            zip,
            mapOf(
                "Advance Wars (USA) (Rev 1).gba" to rom,
                "__MACOSX/._Advance Wars.gba" to ByteArray(10),
                "readme.txt" to "hi".toByteArray(),
                "../evil.gba" to ByteArray(10),
            ),
        )
        val out = File(tmp.root, "ROMs")
        val files = ZipRomExtractor.extract(zip, out)
        assertEquals(listOf("Advance Wars (USA) (Rev 1).gba"), files.map { it.name })
        assertTrue(files[0].readBytes().contentEquals(rom))
        assertFalse(File(tmp.root, "evil.gba").exists())
    }

    @Test
    fun zipEntryNameFilter() {
        assertEquals("a.gba", ZipRomExtractor.romFileName("folder/a.gba"))
        assertNull(ZipRomExtractor.romFileName("folder/"))
        assertNull(ZipRomExtractor.romFileName(".hidden.gba"))
        assertNull(ZipRomExtractor.romFileName("doc.pdf"))
        assertNull(ZipRomExtractor.romFileName("../../x.gba"))
    }

    @Test
    fun scanExtractsZipsAndDetectsSystems() {
        val roms = tmp.newFolder("ROMs")
        File(roms, "Game One.nes").writeBytes(ByteArray(100) { 1 })
        File(roms, "notes.txt").writeText("x")
        File(roms, "Saves").mkdirs()
        File(roms, "Saves/ignored.gba").writeBytes(ByteArray(10))
        makeZip(File(roms, "pack.zip"), mapOf("Inner Game (USA).gba" to ByteArray(200) { 2 }))
        val entries = RomScanner.scan(listOf(roms))
        assertEquals(listOf("Game One", "Inner Game"), entries.map { it.title })
        assertEquals(setOf(GameSystem.NES, GameSystem.GBA), entries.map { it.system }.toSet())
    }

    @Test
    fun systemDetection() {
        assertEquals(GameSystem.GBA, GameSystem.detectFileName("x.GBA"))
        assertEquals(GameSystem.SNES, GameSystem.detectFileName("x.smc"))
        assertEquals(GameSystem.N64, GameSystem.detectFileName("x.z64"))
        assertEquals(GameSystem.NDS, GameSystem.detectFileName("x.nds"))
        assertEquals(GameSystem.GENESIS, GameSystem.detectFileName("x.md"))
        assertNull(GameSystem.detectFileName("x.iso"))
        assertEquals(GameSystem.GB, GameSystem.GBC.category)
        assertEquals("gbc", GameSystem.GB.coreId)
        assertEquals("ds", GameSystem.NDS.coreId)
    }
}

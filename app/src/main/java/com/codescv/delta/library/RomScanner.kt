package com.codescv.delta.library

import com.codescv.delta.model.GameSystem
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** A game in the library (Delta `ROMEntry`). */
@Serializable
data class RomEntry(
    val path: String,
    val systemId: String,
    val fileSize: Long,
    val crc32: Long? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    /** User-visible name override set via "Rename". */
    val customName: String? = null,
) {
    val id: String get() = path
    val system: GameSystem get() = GameSystem.fromId(systemId) ?: GameSystem.GBA
    val file: File get() = File(path)
    val fileName: String get() = file.name
    val baseName: String get() = fileName.substringBeforeLast('.', fileName)
    val title: String get() = customName?.takeIf { it.isNotBlank() } ?: RomScanner.cleanTitle(baseName)
    val crcString: String get() = crc32?.let { "%08X".format(it) } ?: "--------"
}

object RomScanner {
    val skippedFolders = setOf("Saves", "States", "System", "Cores", "Inbox", "Covers", "Skins")
    const val MAX_HASH_BYTES: Long = 128L * 1024 * 1024

    /** Delta's `cleanTitle`: strips `(...)` / `[...]` tags and underscores. */
    fun cleanTitle(base: String): String {
        var title = base
        for ((open, close) in listOf('(' to ')', '[' to ']')) {
            while (true) {
                val start = title.indexOf(open)
                if (start < 0) break
                val end = title.indexOf(close, start + 1)
                if (end < 0) break
                title = title.removeRange(start, end + 1)
            }
        }
        title = title.replace('_', ' ')
        val collapsed = title.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        return collapsed.ifEmpty { base }
    }

    fun crc32(file: File): Long = FileInputStream(file).use { crc32(it) }

    fun crc32(input: InputStream): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            crc.update(buf, 0, n)
        }
        return crc.value
    }

    fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /**
     * OpenVGDB (No-Intro) hashes NES ROMs without their 16-byte iNES header; returns the
     * headerless CRC for iNES files, or null.
     */
    fun headerlessNesCrc(file: File): Long? {
        FileInputStream(file).use { input ->
            val header = ByteArray(16)
            if (input.read(header) != 16) return null
            if (!(header[0] == 0x4E.toByte() && header[1] == 0x45.toByte() && header[2] == 0x53.toByte() && header[3] == 0x1A.toByte())) return null
            val crc = CRC32()
            val buf = ByteArray(64 * 1024)
            var any = false
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                any = true
                crc.update(buf, 0, n)
            }
            return if (any) crc.value else null
        }
    }

    /** Builds an entry for a ROM file, or null if the extension is unsupported / empty. */
    fun makeEntry(file: File, previous: Map<String, RomEntry> = emptyMap()): RomEntry? {
        val system = GameSystem.detectFileName(file.name) ?: return null
        val size = file.length()
        if (size <= 0) return null
        val id = file.absolutePath
        previous[id]?.let { if (it.fileSize == size) return it }
        val crc = if (size <= MAX_HASH_BYTES) runCatching { crc32(file) }.getOrNull() else null
        return RomEntry(id, system.id, size, crc)
    }

    /** Recursively scans [roots] for ROMs (extracting `.zip` archives in place). */
    fun scan(roots: List<File>, previous: Map<String, RomEntry> = emptyMap()): List<RomEntry> {
        val results = LinkedHashMap<String, RomEntry>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (f in children) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (f.name !in skippedFolders) walk(f)
                    continue
                }
                if (f.extension.equals("zip", ignoreCase = true)) {
                    val extracted = ZipRomExtractor.extract(f, f.parentFile ?: dir)
                    for (rom in extracted) makeEntry(rom, previous)?.let { results[it.id] = it }
                    if (extracted.isNotEmpty()) f.delete()
                    continue
                }
                makeEntry(f, previous)?.let { results[it.id] = it }
            }
        }
        roots.forEach { walk(it) }
        return results.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}

/**
 * Extracts supported ROM files from `.zip` archives, preserving the inner entry file name
 * (e.g. `Advance Wars (USA) (Rev 1).gba`) even when the outer archive was renamed, so
 * Libretro thumbnail name matching keeps working.
 */
object ZipRomExtractor {
    /** Android 14+ rejects some entry names by default; Delta handles path safety itself. */
    fun relaxZipPathValidation() {
        try {
            val cls = Class.forName("dalvik.system.ZipPathValidator")
            cls.getMethod("clearCallback").invoke(null)
        } catch (_: Throwable) {
            // Not on Android 14+ (or running on the JVM).
        }
    }

    /** Whether a zip entry is a safe, supported ROM; returns its output file name or null. */
    fun romFileName(entryName: String): String? {
        if (entryName.endsWith("/") || entryName.contains("__MACOSX/") || entryName.contains("..")) return null
        val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')
        if (fileName.isEmpty() || fileName.startsWith(".")) return null
        if (GameSystem.detectFileName(fileName) == null) return null
        return fileName
    }

    fun extract(zip: File, destination: File): List<File> =
        FileInputStream(zip).use { extract(it, destination) }

    fun extract(input: InputStream, destination: File): List<File> {
        relaxZipPathValidation()
        destination.mkdirs()
        val out = ArrayList<File>()
        ZipInputStream(input.buffered()).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val name = romFileName(entry.name)
                if (name == null || entry.isDirectory) {
                    zis.closeEntry()
                    continue
                }
                val target = File(destination, name)
                if (!target.canonicalPath.startsWith(destination.canonicalPath)) {
                    zis.closeEntry()
                    continue
                }
                val tmp = File(destination, ".$name.part")
                tmp.outputStream().use { zis.copyTo(it, 64 * 1024) }
                if (tmp.length() > 0 && (target.delete() || !target.exists()) && tmp.renameTo(target)) {
                    out += target
                } else {
                    tmp.delete()
                }
                zis.closeEntry()
            }
        }
        return out
    }
}

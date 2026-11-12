package com.codescv.delta.library

import com.codescv.delta.model.GameSystem
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Automatic box art, like the original Delta: first the OpenVGDB `releaseCoverFront` matched by
 * ROM CRC32 (bundled `openvgdb_boxart.tsv` -> GameFAQs), then Libretro Thumbnails (official
 * server, jsDelivr mirror, raw GitHub).
 *
 * Covers are stored at `<coversRoot>/<system>/<key>.png`.
 */
class CoverArtFetcher(
    val coversRoot: File,
    private val tableProvider: () -> OpenVgdbTable,
    private val http: HttpClient = UrlConnectionHttpClient(),
) {
    fun interface HttpClient {
        /** Returns (statusCode, body) or throws IOException on network failure. */
        fun get(url: String): Pair<Int, ByteArray>
    }

    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Hosts that answered 403 (e.g. GameFAQs Cloudflare bot wall) are skipped for the session. */
    private val blockedHosts = ConcurrentHashMap<String, Int>()

    fun coverFile(rom: RomEntry): File = File(File(coversRoot, rom.systemId), storageKey(rom) + ".png")

    private fun missMarker(rom: RomEntry): File = File(File(coversRoot, rom.systemId), storageKey(rom) + ".missing")

    fun coverIfExists(rom: RomEntry): File? = coverFile(rom).takeIf { it.isFile && it.length() > 0 }

    fun removeCover(rom: RomEntry) {
        coverFile(rom).delete()
        missMarker(rom).delete()
    }

    fun store(data: ByteArray, rom: RomEntry): File {
        val dest = coverFile(rom)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(dest)) {
            dest.delete()
            tmp.renameTo(dest)
        }
        missMarker(rom).delete()
        return dest
    }

    /**
     * Downloads the cover for [rom] unless one already exists (or a recent lookup failed).
     * Blocking: call from a background dispatcher. Returns the stored file, or null.
     */
    fun fetchCoverIfNeeded(rom: RomEntry, force: Boolean = false, isImage: (ByteArray) -> Boolean = ::looksLikeImage): File? {
        if (!force) coverIfExists(rom)?.let { return it }
        val marker = missMarker(rom)
        if (!force && marker.exists() && System.currentTimeMillis() - marker.lastModified() < MISS_RETRY_INTERVAL_MS) return null
        if (libretroSystemName(rom.system) == null) return null
        val key = coverFile(rom).path
        if (!inFlight.add(key)) return null
        try {
            val extra = if (rom.system == GameSystem.NES) listOfNotNull(runCatching { RomScanner.headerlessNesCrc(rom.file) }.getOrNull()) else emptyList()
            val urls = candidateUrls(rom, tableProvider(), extra)
            val unreachable = HashSet<String>()
            var anyAnswered = false
            for (url in urls) {
                val host = runCatching { URL(url).host }.getOrDefault("")
                if (host in unreachable) continue
                if ((blockedHosts[host] ?: 0) >= BLOCKED_HOST_THRESHOLD) continue
                try {
                    val (status, body) = http.get(url)
                    anyAnswered = true
                    if (status == 403 || status == 429) {
                        // Bot wall / rate limit: fall back to mirrors immediately.
                        blockedHosts.merge(host, 1, Int::plus)
                        continue
                    }
                    if (status == 200 && body.size > MINIMUM_IMAGE_BYTES && isImage(body)) {
                        return store(body, rom)
                    }
                } catch (e: Exception) {
                    unreachable += host
                }
            }
            if (anyAnswered) {
                marker.parentFile?.mkdirs()
                marker.writeBytes(ByteArray(0))
            }
            return null
        } finally {
            inFlight.remove(key)
        }
    }

    companion object {
        const val MISS_RETRY_INTERVAL_MS: Long = 3L * 24 * 60 * 60 * 1000
        const val MINIMUM_IMAGE_BYTES = 512
        const val BLOCKED_HOST_THRESHOLD = 2

        fun libretroSystemName(system: GameSystem): String? = when (system) {
            GameSystem.GBA -> "Nintendo - Game Boy Advance"
            GameSystem.GBC -> "Nintendo - Game Boy Color"
            GameSystem.GB -> "Nintendo - Game Boy"
            GameSystem.NES -> "Nintendo - Nintendo Entertainment System"
            GameSystem.SNES -> "Nintendo - Super Nintendo Entertainment System"
            GameSystem.N64 -> "Nintendo - Nintendo 64"
            GameSystem.NDS -> "Nintendo - Nintendo DS"
            GameSystem.GENESIS -> "Sega - Mega Drive - Genesis"
            GameSystem.MASTER_SYSTEM -> "Sega - Master System - Mark III"
            GameSystem.GAME_GEAR -> "Sega - Game Gear"
            GameSystem.CHIP8 -> null
        }

        /** Replaces characters Libretro Thumbnails forbids in file names with an underscore. */
        fun sanitized(name: String): String {
            val forbidden = setOf('&', '*', '/', ':', '"', '<', '>', '?', '\\', '|')
            return name.map { if (it in forbidden) '_' else it }.joinToString("")
        }

        private val revisionRegex = Regex("""\s*\((?:Rev\s*[^)]*|v\d[^)]*)\)""", RegexOption.IGNORE_CASE)

        fun strippingRevision(baseName: String): String =
            baseName.replace(revisionRegex, "").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

        /** Candidate Libretro thumbnail names (without extension), most specific first. */
        fun candidateFileNames(rom: RomEntry): List<String> {
            val title = RomScanner.cleanTitle(rom.baseName)
            val names = mutableListOf(
                rom.baseName,
                strippingRevision(rom.baseName),
                "$title (USA)",
                "$title (USA, Europe)",
                "$title (World)",
                "$title (Europe)",
                "$title (Japan)",
                title,
            )
            for (prefix in listOf("Pokemon ", "Pokémon ")) {
                if (title.startsWith(prefix) && !title.contains(" - ")) {
                    var rest = title.removePrefix(prefix)
                    if (rest.endsWith(" Version")) rest = rest.removeSuffix(" Version")
                    if (rest.isNotEmpty()) {
                        names += "Pokemon - $rest Version (USA, Europe)"
                        names += "Pokemon - $rest Version (USA)"
                    }
                }
            }
            val seen = LinkedHashSet<String>()
            for (n in names) {
                val clean = sanitized(n.trim())
                if (clean.isNotEmpty()) seen += clean
            }
            return seen.toList()
        }

        /** RFC 3986 path-segment encoding with Delta's allowed set (`-._~()!,'`). */
        fun encodePathComponent(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xFF)
                val ch = c.toChar()
                if (c < 0x80 && (ch.isLetterOrDigit() || ch in "-._~()!,'")) sb.append(ch)
                else sb.append('%').append("%02X".format(c))
            }
            return sb.toString()
        }

        fun openVgdbSystems(system: GameSystem): List<String> = when (system) {
            GameSystem.GBA -> listOf("GBA")
            GameSystem.GBC -> listOf("GBC", "GB")
            GameSystem.GB -> listOf("GB", "GBC")
            GameSystem.NES -> listOf("NES")
            GameSystem.SNES -> listOf("SNES")
            GameSystem.N64 -> listOf("N64")
            GameSystem.NDS -> listOf("NDS")
            GameSystem.GENESIS -> listOf("MD")
            GameSystem.MASTER_SYSTEM -> listOf("SMS")
            GameSystem.GAME_GEAR -> listOf("GG")
            GameSystem.CHIP8 -> emptyList()
        }

        /** `13036` -> `https://gamefaqs.gamespot.com/a/box/0/3/6/13036_front.jpg`. */
        fun gameFaqsCoverUrl(boxId: String): String? {
            if (boxId.isEmpty() || !boxId.all { it in '0'..'9' }) return null
            val digits = boxId.padStart(3, '0')
            val n = digits.length
            return "https://gamefaqs.gamespot.com/a/box/${digits[n - 3]}/${digits[n - 2]}/${digits[n - 1]}/${boxId}_front.jpg"
        }

        fun openVgdbCoverUrls(crc32: Long, system: GameSystem, table: OpenVgdbTable): List<String> {
            for (section in openVgdbSystems(system)) {
                table.lookup(section, crc32)?.let { ids -> return ids.mapNotNull(::gameFaqsCoverUrl) }
            }
            return emptyList()
        }

        fun candidateUrls(rom: RomEntry, table: OpenVgdbTable, extraCrcs: List<Long> = emptyList()): List<String> {
            val system = libretroSystemName(rom.system) ?: return emptyList()
            val encodedSystem = encodePathComponent(system)
            val repo = encodePathComponent(system.replace(' ', '_'))
            val urls = ArrayList<String>()
            for (crc in listOfNotNull(rom.crc32) + extraCrcs) {
                for (u in openVgdbCoverUrls(crc, rom.system, table)) if (u !in urls) urls += u
            }
            for (name in candidateFileNames(rom)) {
                val file = encodePathComponent(name) + ".png"
                urls += "https://thumbnails.libretro.com/$encodedSystem/Named_Boxarts/$file"
                urls += "https://cdn.jsdelivr.net/gh/libretro-thumbnails/$repo@master/Named_Boxarts/$file"
                urls += "https://raw.githubusercontent.com/libretro-thumbnails/$repo/master/Named_Boxarts/$file"
            }
            return urls
        }

        fun storageKey(rom: RomEntry): String {
            val base = sanitized(rom.baseName).replace('.', '_')
            return base.ifEmpty { "cover" }
        }

        /** PNG / JPEG / GIF / WebP magic check (no android.graphics in unit tests). */
        fun looksLikeImage(data: ByteArray): Boolean {
            if (data.size < 12) return false
            val b = data
            val png = b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
            val jpg = b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()
            val gif = b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()
            val webp = b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte()
            return png || jpg || gif || webp
        }

        @Suppress("unused")
        private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")
    }
}

class UrlConnectionHttpClient(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : CoverArtFetcher.HttpClient {
    override fun get(url: String): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "image/png,image/jpeg,image/*")
            conn.setRequestProperty("User-Agent", "Delta-Android/2.0.8")
            val code = conn.responseCode
            val body = if (code in 200..299) conn.inputStream.use { it.readBytes() } else ByteArray(0)
            return code to body
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * `[system: [crc32: [gamefaqs box id]]]` from `openvgdb_boxart.tsv`: `@SYSTEM` headers followed
 * by `<CRC32 hex>\t<box id>[,<box id>...]` lines; `#` lines are comments.
 */
class OpenVgdbTable(private val sections: Map<String, Map<Long, List<String>>>) {
    fun lookup(section: String, crc32: Long): List<String>? = sections[section]?.get(crc32 and 0xFFFFFFFFL)

    val sectionNames: Set<String> get() = sections.keys
    val totalEntries: Int get() = sections.values.sumOf { it.size }

    companion object {
        val EMPTY = OpenVgdbTable(emptyMap())

        fun parse(text: String): OpenVgdbTable {
            val table = HashMap<String, HashMap<Long, List<String>>>()
            var section = ""
            var current = HashMap<Long, List<String>>()
            fun flush() {
                if (section.isNotEmpty()) {
                    val existing = table.getOrPut(section) { HashMap() }
                    for ((k, v) in current) existing.putIfAbsent(k, v)
                }
            }
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trimEnd('\r')
                if (line.isEmpty() || line[0] == '#') continue
                if (line[0] == '@') {
                    flush()
                    section = line.substring(1).trim()
                    current = HashMap()
                    continue
                }
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val crc = line.substring(0, tab).toLongOrNull(16) ?: continue
                current[crc] = line.substring(tab + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            }
            flush()
            return OpenVgdbTable(table)
        }
    }
}

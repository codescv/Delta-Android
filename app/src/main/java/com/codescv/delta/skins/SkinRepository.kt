package com.codescv.delta.skins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import com.codescv.delta.library.ZipRomExtractor
import com.codescv.delta.model.GameSystem
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Where a skin's `info.json` and artwork live (bundled APK assets or an imported directory). */
sealed interface SkinSource {
    val id: String

    /** Bundled official skin: `assets/ControllerSkins/<family>_<file>`. */
    data class Bundled(val family: String) : SkinSource {
        override val id: String get() = "bundled:$family"
    }

    /** Imported `.deltaskin`, extracted under `<files>/Delta/Skins/<family>/<dir>/`. */
    data class Imported(val directory: File) : SkinSource {
        override val id: String get() = "imported:${directory.parentFile?.name}/${directory.name}"
    }
}

data class LoadedSkin(val source: SkinSource, val skin: DeltaSkin)

/**
 * Loads official and imported `.deltaskin` controller skins and rasterizes their vector PDF
 * artwork with [PdfRenderer] into ARGB_8888 bitmaps that start fully transparent, so cut-out
 * skins (landscape, DS) keep the game visible behind them.
 */
class SkinRepository(private val context: Context, private val skinsRoot: File) {
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val parsed = HashMap<String, LoadedSkin>()
    private val pdfCacheDir = File(context.cacheDir, "skin-pdf").apply { mkdirs() }

    @Synchronized
    fun bundled(family: String): LoadedSkin? {
        val key = "bundled:$family"
        parsed[key]?.let { return it }
        return try {
            val json = context.assets.open("ControllerSkins/${family}_info.json").bufferedReader().use { it.readText() }
            LoadedSkin(SkinSource.Bundled(family), DeltaSkin.parse(family, json)).also { parsed[key] = it }
        } catch (e: Exception) {
            Log.w(TAG, "cannot load bundled skin $family", e)
            null
        }
    }

    fun bundledFor(system: GameSystem): LoadedSkin? = system.skinSystem?.let(::bundled)

    /** Imported skins for a skin family (`gba`, `gbc`, ...). */
    @Synchronized
    fun imported(family: String): List<LoadedSkin> {
        val dir = File(skinsRoot, family)
        val dirs = dir.listFiles { f -> f.isDirectory && File(f, "info.json").isFile } ?: return emptyList()
        return dirs.sortedBy { it.name.lowercase() }.mapNotNull { d ->
            val src = SkinSource.Imported(d)
            parsed[src.id] ?: runCatching {
                LoadedSkin(src, DeltaSkin.parse(family, File(d, "info.json").readText())).also { parsed[src.id] = it }
            }.getOrNull()
        }
    }

    fun all(family: String): List<LoadedSkin> = listOfNotNull(bundled(family)) + imported(family)

    fun find(family: String, id: String?): LoadedSkin? =
        if (id == null) bundled(family) else all(family).firstOrNull { it.source.id == id } ?: bundled(family)

    /**
     * Imports a `.deltaskin` (zip) archive. The skin family is detected from
     * `gameTypeIdentifier`, falling back to [fallbackFamily]. Returns the loaded skin.
     */
    fun importDeltaSkin(input: InputStream, displayName: String, fallbackFamily: String?): LoadedSkin {
        ZipRomExtractor.relaxZipPathValidation()
        val staging = File(skinsRoot, ".import-${System.nanoTime()}").apply { mkdirs() }
        try {
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    if (e.isDirectory || name.isEmpty() || name.startsWith(".") || e.name.contains("..") || e.name.contains("__MACOSX")) {
                        zis.closeEntry()
                        continue
                    }
                    File(staging, name).outputStream().use { zis.copyTo(it) }
                    zis.closeEntry()
                }
            }
            val info = File(staging, "info.json")
            if (!info.isFile) throw IllegalArgumentException("Not a .deltaskin file (info.json missing).")
            val probe = DeltaSkin.parse(fallbackFamily ?: "gba", info.readText())
            val family = DeltaSkin.skinSystemForGameType(probe.gameTypeIdentifier) ?: fallbackFamily
                ?: throw IllegalArgumentException("Unknown game system for skin ${probe.name}.")
            val dirName = (probe.identifier.ifBlank { displayName }).replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(File(skinsRoot, family), dirName)
            dest.deleteRecursively()
            dest.parentFile?.mkdirs()
            if (!staging.renameTo(dest)) {
                staging.copyRecursively(dest, overwrite = true)
            }
            synchronized(this) { parsed.remove(SkinSource.Imported(dest).id) }
            cache.evictAll()
            return LoadedSkin(SkinSource.Imported(dest), DeltaSkin.parse(family, File(dest, "info.json").readText()))
        } finally {
            staging.deleteRecursively()
        }
    }

    fun delete(skin: LoadedSkin) {
        val src = skin.source
        if (src is SkinSource.Imported) {
            src.directory.deleteRecursively()
            synchronized(this) { parsed.remove(src.id) }
        }
    }

    /** Rasterizes a skin asset (PDF or PNG) at exactly [width] x [height] pixels. */
    fun render(skin: LoadedSkin, fileName: String, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val key = "${skin.source.id}|$fileName|${width}x$height"
        cache.get(key)?.let { return it }
        val bmp = try {
            if (fileName.endsWith(".pdf", ignoreCase = true) || !fileName.contains('.')) {
                val pdf = pdfFile(skin, if (fileName.contains('.')) fileName else "$fileName.pdf") ?: return null
                renderPdf(pdf, width, height)
            } else {
                openAsset(skin, fileName)?.use { BitmapFactory.decodeStream(it) }?.let {
                    if (it.width == width && it.height == height) it else Bitmap.createScaledBitmap(it, width, height, true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "render $fileName failed", e)
            null
        } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    private fun openAsset(skin: LoadedSkin, fileName: String): InputStream? = when (val s = skin.source) {
        is SkinSource.Bundled -> runCatching { context.assets.open("ControllerSkins/${s.family}_$fileName") }.getOrNull()
        is SkinSource.Imported -> File(s.directory, fileName).takeIf { it.isFile }?.inputStream()
    }

    /** PdfRenderer needs a seekable file that starts at offset 0: copy bundled PDFs to cache. */
    private fun pdfFile(skin: LoadedSkin, fileName: String): File? = when (val s = skin.source) {
        is SkinSource.Imported -> File(s.directory, fileName).takeIf { it.isFile }
        is SkinSource.Bundled -> {
            val out = File(pdfCacheDir, "${s.family}_$fileName")
            if (!out.isFile || out.length() == 0L) {
                runCatching {
                    context.assets.open("ControllerSkins/${s.family}_$fileName").use { input ->
                        val tmp = File(pdfCacheDir, out.name + ".tmp")
                        tmp.outputStream().use { input.copyTo(it) }
                        tmp.renameTo(out)
                    }
                }
            }
            out.takeIf { it.isFile }
        }
    }

    companion object {
        private const val TAG = "DeltaSkins"

        /** Renders page 1 into a transparent ARGB_8888 bitmap, stretched to width x height. */
        fun renderPdf(file: File, width: Int, height: Int): Bitmap? {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount < 1) return null
                    renderer.openPage(0).use { page ->
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.TRANSPARENT)
                        val m = Matrix()
                        m.setScale(width.toFloat() / page.width, height.toFloat() / page.height)
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bmp
                    }
                }
            }
        }

        /** Renders a bundled DeltaAssets PDF (e.g. ControllerIcon_GBA) at a given height. */
        fun renderAssetPdf(context: Context, assetPath: String, heightPx: Int): Bitmap? = try {
            val out = File(context.cacheDir, "asset-pdf/" + assetPath.replace('/', '_'))
            if (!out.isFile) {
                out.parentFile?.mkdirs()
                context.assets.open(assetPath).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
            ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    r.openPage(0).use { page ->
                        val w = (page.width.toFloat() * heightPx / page.height).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, heightPx, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.TRANSPARENT)
                        val m = Matrix().apply { setScale(w.toFloat() / page.width, heightPx.toFloat() / page.height) }
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}

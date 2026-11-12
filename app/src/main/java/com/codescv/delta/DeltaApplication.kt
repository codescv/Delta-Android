package com.codescv.delta

import android.app.Application
import com.codescv.delta.library.CoverArtFetcher
import com.codescv.delta.library.LibraryStore
import com.codescv.delta.library.OpenVgdbTable
import com.codescv.delta.saves.SaveFileManager
import com.codescv.delta.settings.PreferencesStore
import com.codescv.delta.skins.SkinRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Process-wide singletons (the Android counterpart of Delta's DeltaRootView environment).
 * Data layout (design §6) lives under `filesDir`:
 * `ROMs/ Saves/ States/ System/ Covers/ Skins/ Screenshots/ library.json preferences.json`.
 */
class AppContainer(val app: Application) {
    val root: File = app.filesDir
    val romsDir: File get() = File(root, "ROMs").apply { mkdirs() }
    val systemDir: File get() = File(root, "System").apply { mkdirs() }
    val coversDir: File get() = File(root, "Covers").apply { mkdirs() }
    val skinsDir: File get() = File(root, "Skins").apply { mkdirs() }
    val screenshotsDir: File get() = File(root, "Screenshots").apply { mkdirs() }
    val coreCacheDir: File get() = File(app.cacheDir, "delta").apply { mkdirs() }

    /** Background scope for library scans, cover downloads and file I/O. */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val preferences = PreferencesStore(File(root, "preferences.json"))
    val saves = SaveFileManager(root)

    val openVgdb: OpenVgdbTable by lazy {
        runCatching {
            app.assets.open("OpenVGDB/openvgdb_boxart.tsv").bufferedReader().use { OpenVgdbTable.parse(it.readText()) }
        }.getOrDefault(OpenVgdbTable.EMPTY)
    }

    val covers = CoverArtFetcher(coversDir, { openVgdb })
    val library = LibraryStore(root, File(root, "library.json"), saves, covers)
    val skins = SkinRepository(app, skinsDir)
}

class DeltaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: DeltaApplication
            private set

        val container: AppContainer get() = instance.container
    }
}

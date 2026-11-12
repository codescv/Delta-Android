package com.codescv.delta.settings

import com.codescv.delta.cheats.CheatCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Delta video filters (`VideoFilter`). */
enum class VideoFilter(val raw: String, val title: String) {
    SHARP_PIXELS("sharpPixels", "Sharp Pixels"),
    LCD_GRID("lcdGrid", "LCD Grid"),
    CRT_SCANLINES("crtScanlines", "CRT Scanlines"),
    SMOOTH_BILINEAR("smoothBilinear", "Smooth Bilinear");

    companion object {
        fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw } ?: SHARP_PIXELS
    }
}

@Serializable
data class ExperimentalFeature(
    val key: String,
    val name: String,
    val summary: String,
    val detailedDescription: String? = null,
    val isEnabled: Boolean = false,
    val options: Map<String, String> = emptyMap(),
)

/** All persisted preferences (`AppPreferences` keys, same defaults as iOS Delta 2.0.5). */
@Serializable
data class AppPreferences(
    val filter: String = "sharpPixels",
    val portraitSkinsBySystem: Map<String, String> = emptyMap(),
    val landscapeSkinsBySystem: Map<String, String> = emptyMap(),
    val fastForwardMode: String = "toggle",
    val fastForwardMultiplier: Int = 2,
    val haptics: Boolean = true,
    val thumbstickHaptics: Boolean = true,
    val buttonClickSound: Boolean = false,
    val controllerOpacity: Double = 0.70,
    val respectSilentMode: Boolean = false,
    val integerScaling: Boolean = false,
    val autoResume: Boolean = true,
    val alwaysShowTouchControls: Boolean = false,
    val volume: Double = 1.0,
    val selectedAppIconName: String = "ModernIcon",
    val preferredWFCServer: String = "167.86.108.126",
    val retroAchievementsUsername: String = "",
    val retroAchievementsHardcore: Boolean = false,
    val coreOptions: Map<String, Map<String, String>> = emptyMap(),
    val cheatsByROM: Map<String, List<CheatCode>> = emptyMap(),
    val experimentalFeatures: List<ExperimentalFeature> = ExperimentalFeatures.defaults(),
    val ndsBiosMode: String = "builtin",
    val lastLibraryPage: String? = null,
    val updateCheckOnLaunch: Boolean = true,
) {
    fun isExperimentalEnabled(key: String) = experimentalFeatures.firstOrNull { it.key == key }?.isEnabled ?: false
    fun experimentalOption(key: String, option: String) = experimentalFeatures.firstOrNull { it.key == key }?.options?.get(option)
}

object ExperimentalFeatures {
    /** All 15 official Delta experimental features, unlocked, in declaration order. */
    fun defaults(): List<ExperimentalFeature> = listOf(
        ExperimentalFeature("airPlaySkins", "AirPlay Skins", "Customize the appearance of games when AirPlaying to your TV.", isEnabled = true),
        ExperimentalFeature("showStatusBar", "Show Status Bar", "Enable to show the Status Bar during gameplay."),
        ExperimentalFeature(
            "toastNotifications", "Toast Notifications",
            "Show toast notifications as a confirmation for various actions, such as saving your game or loading a save state.",
            isEnabled = true, options = mapOf("duration" to "1.5s", "stateSave" to "On", "stateLoad" to "On", "fastForward" to "On"),
        ),
        ExperimentalFeature("reviewSaveStates", "Review Save States", "Review recent Save States to make sure they are associated with the correct game."),
        ExperimentalFeature("repairDatabase", "Repair Database", "Repair invalid relationships in Delta's game database on next app launch."),
        ExperimentalFeature(
            "skinDebugging", "Skin Debugging", "Enable features useful for mapping controller skins.",
            options = mapOf("showHitBoxes" to "Off", "ignoreSafeAreas" to "Off"),
        ),
        ExperimentalFeature(
            "reverseScreens", "Reverse Controller Skin Screens",
            "Dynamically reverse the order of screen inputFrames in controller skins. Can be used to “flip” between DS screens.",
        ),
        ExperimentalFeature("showTouches", "Show Touches", "Visually show touches. Useful for screen recordings and tutorials."),
        ExperimentalFeature("metal", "Metal Renderer", "Use Metal to render games instead of OpenGL ES. Does not apply to N64 games.", isEnabled = true),
        ExperimentalFeature(
            "Lu", "Lu",
            "Ask Lu questions about your games to receive helpful tips, strategies, and interesting facts tailored to the games you're playing.",
            detailedDescription = "Lu learns from your questions and preferences to provide personalized advice for the games you're playing.",
            isEnabled = true,
        ),
        ExperimentalFeature("showWhatsNew", "Show What’s New", "Enable this to show What’s New on next launch."),
        ExperimentalFeature("screenshotsAlbum", "Delta Screenshots Album", "Save game screenshots to dedicated “Delta Screenshots” album.", isEnabled = true),
        ExperimentalFeature(
            "operatorDevice", "Epilogue Operator",
            "Enable support for Epilogue Operator devices to play games directly from cartridges. Requires an app restart to take effect.",
            isEnabled = true,
        ),
        ExperimentalFeature(
            "libraryExport", "Library Export",
            "Allow other apps to fetch your game library via the “delta://gameInfo?scheme=<callerScheme>” URL request.",
            isEnabled = true,
        ),
        ExperimentalFeature(
            "customFilters", "Custom Filters",
            "Recreate the look of original CRT TVs and handheld screens with Core Image shaders, or import your own shader files.",
            isEnabled = true, options = mapOf("shader" to "CRT Pixellate"),
        ),
    )

    /** Merges persisted features with the canonical list (new features appear, order kept). */
    fun merge(saved: List<ExperimentalFeature>): List<ExperimentalFeature> {
        val byKey = saved.associateBy { it.key }
        return defaults().map { d ->
            val s = byKey[d.key] ?: return@map d
            d.copy(isEnabled = s.isEnabled, options = d.options + s.options.filterKeys { it in d.options })
        }
    }
}

/** `preferences.json` store with atomic writes and tolerant decoding. */
class PreferencesStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true; coerceInputValues = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppPreferences> = _state.asStateFlow()
    val value: AppPreferences get() = _state.value

    private fun load(): AppPreferences {
        val loaded = runCatching { json.decodeFromString<AppPreferences>(file.readText()) }.getOrNull() ?: AppPreferences()
        return loaded.copy(experimentalFeatures = ExperimentalFeatures.merge(loaded.experimentalFeatures))
    }

    @Synchronized
    fun update(transform: (AppPreferences) -> AppPreferences) {
        val next = transform(_state.value)
        if (next == _state.value) return
        _state.value = next
        persist(next)
    }

    fun setExperimental(key: String, enabled: Boolean) = update { p ->
        p.copy(experimentalFeatures = p.experimentalFeatures.map { if (it.key == key) it.copy(isEnabled = enabled) else it })
    }

    fun setExperimentalOption(key: String, option: String, value: String) = update { p ->
        p.copy(experimentalFeatures = p.experimentalFeatures.map { if (it.key == key) it.copy(options = it.options + (option to value)) else it })
    }

    private fun persist(p: AppPreferences) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(p))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}

package com.codescv.delta.ui.settings

import com.codescv.delta.BuildConfig
import com.codescv.delta.i18n.DeltaI18n
import com.codescv.delta.i18n.DeltaStrings
import com.codescv.delta.i18n.StrKey
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.settings.ExperimentalFeature

/** Static Settings content and pure helpers (ui_spec §6, design §5.5). */
object SettingsData {
    val VERSION_STRING: String =
        "${BuildConfig.VERSION_NAME.removeSuffix("-DEBUG")} (Build ${BuildConfig.VERSION_CODE})"
    val FOOTER: String = "Delta $VERSION_STRING"
    const val GITHUB_FORK = "github.com/codescv/Delta-Android"

    // ---- Controllers / Fast Forward -------------------------------------------------------

    /** `FastForwardMode` raw -> title. */
    val fastForwardModes = listOf("hold" to "Hold to Fast-Forward", "toggle" to "Tap to Toggle")
    val fastForwardMultipliers = listOf(2, 3, 4)

    fun fastForwardModes(strings: DeltaStrings): List<Pair<String, String>> = listOf(
        "hold" to strings[StrKey.CTRL_FF_HOLD],
        "toggle" to strings[StrKey.CTRL_FF_TOGGLE],
    )

    fun fastForwardModeTitle(raw: String, strings: DeltaStrings = DeltaI18n.currentStrings()): String =
        fastForwardModes(strings).firstOrNull { it.first == raw }?.second ?: strings[StrKey.CTRL_FF_TOGGLE]

    // ---- Touch & Haptics --------------------------------------------------------------------

    val hapticStrengths = listOf("light" to "Light", "medium" to "Medium", "strong" to "Strong")

    fun hapticStrengths(strings: DeltaStrings): List<Pair<String, String>> = listOf(
        "light" to strings[StrKey.HAPTICS_LIGHT],
        "medium" to strings[StrKey.HAPTICS_MEDIUM],
        "strong" to strings[StrKey.HAPTICS_STRONG],
    )

    fun hapticStrengthIndex(raw: String) = hapticStrengths.indexOfFirst { it.first == raw }.let { if (it < 0) 1 else it }

    // ---- Skins ------------------------------------------------------------------------------

    /** Controller skin opacity slider: 0.15…1.0 step 0.05. */
    const val OPACITY_MIN = 0.15f
    const val OPACITY_MAX = 1.0f
    const val OPACITY_STEPS = 16 // (1.0 - 0.15) / 0.05 = 17 intervals -> 16 intermediate stops

    fun snapOpacity(value: Float): Double {
        val clamped = value.coerceIn(OPACITY_MIN, OPACITY_MAX)
        return Math.round(clamped / 0.05f) * 0.05
    }

    fun opacityLabel(opacity: Double) = "${Math.round(opacity * 100)}%"

    /** Fallback badge text per skin family when a skin option carries none. */
    fun familyBadge(family: String) = when (family) {
        "nes" -> "NINTENDO"
        "snes" -> "SUPER NINTENDO"
        "n64" -> "NINTENDO 64"
        "gbc" -> "GAME BOY COLOR"
        "gba" -> "GAME BOY ADVANCE"
        "nds" -> "NINTENDO DS"
        "genesis" -> "SEGA GENESIS"
        else -> family.uppercase()
    }

    /** Mini console tile colors (shellTop, shellBottom, accent, bezel, bezelText) per family. */
    fun familyPalette(family: String): LongArray = when (family) {
        "nes" -> longArrayOf(0xFFD9D9D9, 0xFFA9A9A9, 0xFFC62828, 0xFF2B2B2B, 0xFFFFFFFF)
        "snes" -> longArrayOf(0xFFD6D6DE, 0xFFA8A8B4, 0xFF5B3FA8, 0xFF3D3D46, 0xFFFFFFFF)
        "n64" -> longArrayOf(0xFF4A4A4F, 0xFF262629, 0xFF2E7D32, 0xFF1B1B1D, 0xFFE0E0E0)
        "gbc" -> longArrayOf(0xFF8E5BD8, 0xFF5E2FA8, 0xFFFFD54F, 0xFF2D2D3A, 0xFFFFFFFF)
        "gba" -> longArrayOf(0xFF8A4FFF, 0xFF5B2BC0, 0xFFFFFFFF, 0xFF2B1D4A, 0xFFE8DEFF)
        "nds" -> longArrayOf(0xFF3A3A3F, 0xFF1F1F22, 0xFF90CAF9, 0xFF101012, 0xFFE0E0E0)
        "genesis" -> longArrayOf(0xFF2F2F33, 0xFF141416, 0xFFE53935, 0xFF0B0B0C, 0xFFFFFFFF)
        else -> longArrayOf(0xFF3A3A3C, 0xFF1C1C1E, 0xFF8A2BE2, 0xFF2C2C2E, 0xFFFFFFFF)
    }

    // ---- Cores ------------------------------------------------------------------------------

    /** `[nes, snes, n64, gbc, gba, nds, genesis, chip8]`. */
    val coreSystems = listOf(
        GameSystem.NES, GameSystem.SNES, GameSystem.N64, GameSystem.GBC,
        GameSystem.GBA, GameSystem.NDS, GameSystem.GENESIS, GameSystem.CHIP8,
    )

    fun coreValue(system: GameSystem) = system.coreDisplayName.uppercase()

    const val MELONDS_ROW = "Nintendo DS & DSi Core (melonDS)"
    const val BUILT_IN_BIOS = "Built-in BIOS"
    const val BUILT_IN_BIOS_ACTIVE = "Built-in BIOS active"

    /** melonDS BIOS / firmware files (`DeltaBIOSFile.allBIOSFiles`). */
    data class BiosFile(val filename: String, val sizeDescription: String, val isDSi: Boolean, val hasBuiltIn: Boolean)

    val biosFiles = listOf(
        BiosFile("bios7.bin", "16 KB", isDSi = false, hasBuiltIn = true),
        BiosFile("bios9.bin", "4 KB", isDSi = false, hasBuiltIn = true),
        BiosFile("firmware.bin", "128/256/512 KB", isDSi = false, hasBuiltIn = false),
        BiosFile("dsi_bios7.bin", "64 KB", isDSi = true, hasBuiltIn = false),
        BiosFile("dsi_bios9.bin", "64 KB", isDSi = true, hasBuiltIn = false),
        BiosFile("dsi_firmware.bin", "128 KB", isDSi = true, hasBuiltIn = false),
        BiosFile("dsi_nand.bin", "240 MB", isDSi = true, hasBuiltIn = false),
    )

    /** Status shown next to a BIOS file; [importedBytes] is the imported file size or null. */
    fun biosStatus(
        file: BiosFile,
        importedBytes: Long?,
        strings: DeltaStrings = DeltaI18n.currentStrings(),
    ): String = when {
        importedBytes != null -> strings.format(StrKey.BIOS_STATUS_IMPORTED, formatSize(importedBytes))
        file.hasBuiltIn -> strings.format(StrKey.BIOS_STATUS_OPTIONAL_BUILTIN, file.sizeDescription, strings[StrKey.CORES_BUILT_IN_BIOS_ACTIVE])
        else -> strings.format(StrKey.BIOS_STATUS_OPTIONAL, file.sizeDescription)
    }

    /** `ndsBiosMode` values. */
    val ndsBiosModes = listOf("builtin" to "Built-in", "external" to "Imported Files")

    fun ndsBiosModes(strings: DeltaStrings): List<Pair<String, String>> = listOf(
        "builtin" to strings[StrKey.BIOS_MODE_BUILTIN],
        "external" to strings[StrKey.BIOS_MODE_IMPORTED],
    )

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
        bytes >= 1024L -> "${bytes / 1024L} KB"
        else -> "$bytes B"
    }

    /** parallel-n64 core options surfaced directly in Cores › Nintendo 64. */
    const val N64_CORE_ID = "n64"
    const val N64_ASPECT_KEY = "parallel-n64-aspectratiohint"
    const val N64_RESOLUTION_KEY = "parallel-n64-screensize"

    // ---- Online Multiplayer ----------------------------------------------------------------

    data class WfcServer(val name: String, val dns: String, val summary: String) {
        val title get() = "$name ($dns)"
    }

    val wfcServers = listOf(
        WfcServer("Wiimmfi", "167.86.108.126", "Most popular Nintendo WFC replacement server."),
        WfcServer("AltWFC / WFCZwei", "172.104.88.237", "Community-hosted Nintendo DS WFC server."),
        WfcServer("Kaeru WFC", "178.62.43.212", "Supports Flipnote Hatena and classic DS Wi-Fi games."),
    )

    fun wfcSummary(server: WfcServer, strings: DeltaStrings = DeltaI18n.currentStrings()): String = when (server.dns) {
        "167.86.108.126" -> strings[StrKey.WFC_WIIMMFI_SUMMARY]
        "172.104.88.237" -> strings[StrKey.WFC_ALTWFC_SUMMARY]
        "178.62.43.212" -> strings[StrKey.WFC_KAERU_SUMMARY]
        else -> server.summary
    }

    fun wfcTitle(dns: String) = wfcServers.firstOrNull { it.dns == dns }?.title ?: dns

    // ---- Credits ----------------------------------------------------------------------------

    val contributors = listOf(
        "Riley Testut" to "Developer",
        "Caroline Moore" to "Developer",
        "Shane Gill" to "Developer",
        "Grant Gliner" to "Pause Menu Icons",
        "Eric Lewis" to "Dark Mode",
        "Chris Rittenhouse" to "Genesis Skin, Show Status Bar, Game Screenshots, Toast Notifications",
        "Noah Keck" to "CheatBase",
        "Ian Clawson" to "Local Multiplayer",
    )

    fun contributors(strings: DeltaStrings): List<Pair<String, String>> = listOf(
        "Riley Testut" to strings[StrKey.CREDIT_ROLE_DEVELOPER],
        "Caroline Moore" to strings[StrKey.CREDIT_ROLE_DEVELOPER],
        "Shane Gill" to strings[StrKey.CREDIT_ROLE_DEVELOPER],
        "Grant Gliner" to strings[StrKey.CREDIT_ROLE_PAUSE_ICONS],
        "Eric Lewis" to strings[StrKey.CREDIT_ROLE_DARK_MODE],
        "Chris Rittenhouse" to strings[StrKey.CREDIT_ROLE_CHRIS],
        "Noah Keck" to strings[StrKey.CREDIT_ROLE_CHEATBASE],
        "Ian Clawson" to strings[StrKey.CREDIT_ROLE_LOCAL_MP],
    )

    /** License *names* only (full texts live in THIRD_PARTY.md). */
    val licenses = listOf(
        "Delta / DeltaCore" to "AGPLv3",
        "melonDS" to "GPLv3",
        "VBA-Next" to "GPLv2",
        "Gambatte" to "GPLv2",
        "QuickNES" to "LGPLv2.1",
        "Snes9x 2005" to "Snes9x License (non-commercial)",
        "ParaLLEl N64 / Mupen64Plus" to "GPLv2",
        "Genesis Plus GX" to "Genesis Plus GX License (non-commercial)",
        "libretro-common" to "MIT",
        "Lemuroid (update/feedback module)" to "GPLv3",
    )

    // ---- Experimental Features -------------------------------------------------------------

    /** How a persisted feature is presented on Android (design §5.5 analogs). */
    data class FeaturePresentation(val name: String, val summary: String, val note: String? = null)

    fun experimentalTitle(
        key: String,
        fallback: String = key,
        strings: DeltaStrings = DeltaI18n.currentStrings(),
    ): String = when (key) {
        "airPlaySkins" -> strings[StrKey.EXP_AIRPLAY_NAME]
        "showStatusBar" -> strings[StrKey.EXP_STATUS_BAR_NAME]
        "toastNotifications" -> strings[StrKey.EXP_TOASTS_NAME]
        "reviewSaveStates" -> strings[StrKey.EXP_REVIEW_STATES_NAME]
        "repairDatabase" -> strings[StrKey.EXP_REPAIR_DB_NAME]
        "skinDebugging" -> strings[StrKey.EXP_SKIN_DEBUG_NAME]
        "reverseScreens" -> strings[StrKey.EXP_REVERSE_SCREENS_NAME]
        "showTouches" -> strings[StrKey.EXP_SHOW_TOUCHES_NAME]
        "metal" -> strings[StrKey.EXP_GPU_NAME]
        "Lu" -> strings[StrKey.EXP_LU_NAME]
        "showWhatsNew" -> strings[StrKey.EXP_WHATS_NEW_NAME]
        "screenshotsAlbum" -> strings[StrKey.EXP_SCREENSHOTS_ALBUM_NAME]
        "operatorDevice" -> strings[StrKey.EXP_OPERATOR_NAME]
        "libraryExport" -> strings[StrKey.EXP_LIBRARY_EXPORT_NAME]
        "customFilters" -> strings[StrKey.EXP_CUSTOM_FILTERS_NAME]
        else -> fallback
    }

    fun present(
        feature: ExperimentalFeature,
        strings: DeltaStrings = DeltaI18n.currentStrings(),
    ): FeaturePresentation = when (feature.key) {
        "metal" -> FeaturePresentation(strings[StrKey.EXP_GPU_NAME], strings[StrKey.EXP_GPU_SUMMARY])
        "airPlaySkins" -> FeaturePresentation(
            strings[StrKey.EXP_AIRPLAY_NAME],
            strings[StrKey.EXP_AIRPLAY_SUMMARY] + strings[StrKey.EXP_EXTERNAL_DISPLAYS_SUFFIX],
        )
        "showStatusBar" -> FeaturePresentation(strings[StrKey.EXP_STATUS_BAR_NAME], strings[StrKey.EXP_STATUS_BAR_SUMMARY])
        "toastNotifications" -> FeaturePresentation(strings[StrKey.EXP_TOASTS_NAME], strings[StrKey.EXP_TOASTS_SUMMARY])
        "reviewSaveStates" -> FeaturePresentation(strings[StrKey.EXP_REVIEW_STATES_NAME], strings[StrKey.EXP_REVIEW_STATES_SUMMARY])
        "repairDatabase" -> FeaturePresentation(strings[StrKey.EXP_REPAIR_DB_NAME], strings[StrKey.EXP_REPAIR_DB_SUMMARY])
        "skinDebugging" -> FeaturePresentation(strings[StrKey.EXP_SKIN_DEBUG_NAME], strings[StrKey.EXP_SKIN_DEBUG_SUMMARY])
        "reverseScreens" -> FeaturePresentation(strings[StrKey.EXP_REVERSE_SCREENS_NAME], strings[StrKey.EXP_REVERSE_SCREENS_SUMMARY])
        "showTouches" -> FeaturePresentation(strings[StrKey.EXP_SHOW_TOUCHES_NAME], strings[StrKey.EXP_SHOW_TOUCHES_SUMMARY])
        "Lu" -> FeaturePresentation(strings[StrKey.EXP_LU_NAME], strings[StrKey.EXP_LU_SUMMARY])
        "showWhatsNew" -> FeaturePresentation(strings[StrKey.EXP_WHATS_NEW_NAME], strings[StrKey.EXP_WHATS_NEW_SUMMARY])
        "screenshotsAlbum" -> FeaturePresentation(strings[StrKey.EXP_SCREENSHOTS_ALBUM_NAME], strings[StrKey.EXP_SCREENSHOTS_ALBUM_SUMMARY])
        "operatorDevice" -> FeaturePresentation(
            strings[StrKey.EXP_OPERATOR_NAME],
            strings[StrKey.EXP_OPERATOR_SUMMARY],
            note = strings[StrKey.EXP_NOT_AVAILABLE_NOTE],
        )
        "libraryExport" -> FeaturePresentation(strings[StrKey.EXP_LIBRARY_EXPORT_NAME], strings[StrKey.EXP_LIBRARY_EXPORT_SUMMARY])
        "customFilters" -> FeaturePresentation(strings[StrKey.EXP_CUSTOM_FILTERS_NAME], strings[StrKey.EXP_CUSTOM_FILTERS_SUMMARY])
        else -> FeaturePresentation(feature.name, feature.summary)
    }

    /** Footer of the feature detail screen: summary (+ "\n\n" detailed description) (+ note). */
    fun detailFooter(
        feature: ExperimentalFeature,
        strings: DeltaStrings = DeltaI18n.currentStrings(),
    ): String {
        val p = present(feature, strings)
        val detail = if (feature.key == "Lu") strings[StrKey.EXP_LU_DETAIL] else feature.detailedDescription
        val base = detail?.let { p.summary + "\n\n" + it } ?: p.summary
        return p.note?.let { "$base\n\n$it" } ?: base
    }

    fun experimentalOptionLabel(optionKey: String, strings: DeltaStrings): String = when (optionKey) {
        "duration" -> strings[StrKey.EXP_OPT_DURATION]
        "stateSave" -> strings[StrKey.EXP_OPT_STATE_SAVE]
        "stateLoad" -> strings[StrKey.EXP_OPT_STATE_LOAD]
        "fastForward" -> strings[StrKey.EXP_OPT_FAST_FORWARD]
        "showHitBoxes" -> strings[StrKey.EXP_OPT_SHOW_HIT_BOXES]
        "ignoreSafeAreas" -> strings[StrKey.EXP_OPT_IGNORE_SAFE_AREAS]
        "shader" -> strings[StrKey.EXP_OPT_SHADER]
        else -> optionKey
    }

    fun experimentalOptionValue(rawValue: String, strings: DeltaStrings): String = when (rawValue) {
        "On" -> strings[StrKey.COMMON_ON]
        "Off" -> strings[StrKey.COMMON_OFF]
        else -> rawValue
    }

    /** Next value when an option row is tapped, or null when the option is read-only. */
    fun nextOptionValue(option: String, current: String): String? = when {
        current == "On" -> "Off"
        current == "Off" -> "On"
        option == "duration" -> {
            val choices = listOf("1.0s", "1.5s", "2.0s", "3.0s")
            choices[(choices.indexOf(current) + 1).mod(choices.size)]
        }
        else -> null
    }

    // ---- Controller key mapping -------------------------------------------------------------

    fun buttonLabel(
        button: VirtualButton,
        strings: DeltaStrings = DeltaI18n.currentStrings(),
    ): String = when (button) {
        VirtualButton.START -> "Start"
        VirtualButton.SELECT -> "Select"
        VirtualButton.UP -> strings[StrKey.CTRL_BTN_UP]
        VirtualButton.DOWN -> strings[StrKey.CTRL_BTN_DOWN]
        VirtualButton.LEFT -> strings[StrKey.CTRL_BTN_LEFT]
        VirtualButton.RIGHT -> strings[StrKey.CTRL_BTN_RIGHT]
        else -> button.name
    }

    /**
     * Assigns [keyCode] to [button] in the effective mapping. A button that already used
     * [keyCode] takes over [button]'s previous key, so no key ever drives two buttons.
     */
    fun remap(effective: Map<VirtualButton, Int>, button: VirtualButton, keyCode: Int): Map<VirtualButton, Int> {
        val previous = effective[button]
        return effective.mapValues { (b, k) ->
            when {
                b == button -> keyCode
                k == keyCode && previous != null -> previous
                else -> k
            }
        }
    }

    /** Persistable overrides (`gamepadMappings[player]`): only entries that differ from [defaults]. */
    fun overridesFor(effective: Map<VirtualButton, Int>, defaults: Map<VirtualButton, Int>): Map<String, Int> =
        effective.filter { (b, k) -> defaults[b] != k }.mapKeys { it.key.name }

    /** Updates `gamepadMappings` for [player] with [overrides] (empty overrides remove the player). */
    fun withPlayerOverrides(
        all: Map<String, Map<String, Int>>,
        player: Int,
        overrides: Map<String, Int>,
    ): Map<String, Map<String, Int>> =
        if (overrides.isEmpty()) all - player.toString() else all + (player.toString() to overrides)
}

package com.codescv.delta.ui.settings

import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.settings.ExperimentalFeature

/** Static Settings content and pure helpers (ui_spec §6, design §5.5). */
object SettingsData {
    const val VERSION_STRING = "2.0.8 (Build 208)"
    const val FOOTER = "Delta $VERSION_STRING"
    const val GITHUB_FORK = "github.com/codescv/Delta-Android"

    // ---- Controllers / Fast Forward -------------------------------------------------------

    /** `FastForwardMode` raw -> title. */
    val fastForwardModes = listOf("hold" to "Hold to Fast-Forward", "toggle" to "Tap to Toggle")
    val fastForwardMultipliers = listOf(2, 3, 4)

    fun fastForwardModeTitle(raw: String) = fastForwardModes.firstOrNull { it.first == raw }?.second ?: "Tap to Toggle"

    // ---- Touch & Haptics --------------------------------------------------------------------

    val hapticStrengths = listOf("light" to "Light", "medium" to "Medium", "strong" to "Strong")

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
    fun biosStatus(file: BiosFile, importedBytes: Long?): String = when {
        importedBytes != null -> "Imported (${formatSize(importedBytes)})"
        file.hasBuiltIn -> "${file.sizeDescription} (Optional — $BUILT_IN_BIOS_ACTIVE)"
        else -> "${file.sizeDescription} (Optional)"
    }

    /** `ndsBiosMode` values. */
    val ndsBiosModes = listOf("builtin" to "Built-in", "external" to "Imported Files")

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

    fun present(feature: ExperimentalFeature): FeaturePresentation = when (feature.key) {
        "metal" -> FeaturePresentation(
            "GPU Renderer",
            "Use the GPU (OpenGL ES) to render games instead of the software renderer. Does not apply to N64 games.",
        )
        "airPlaySkins" -> FeaturePresentation(feature.name, feature.summary + " (External displays)")
        "operatorDevice" -> FeaturePresentation(feature.name, feature.summary, note = "Not available on this device")
        else -> FeaturePresentation(feature.name, feature.summary)
    }

    /** Footer of the feature detail screen: summary (+ "\n\n" detailed description) (+ note). */
    fun detailFooter(feature: ExperimentalFeature): String {
        val p = present(feature)
        val base = feature.detailedDescription?.let { p.summary + "\n\n" + it } ?: p.summary
        return p.note?.let { "$base\n\n$it" } ?: base
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

    fun buttonLabel(button: VirtualButton): String = when (button) {
        VirtualButton.START -> "Start"
        VirtualButton.SELECT -> "Select"
        VirtualButton.UP -> "Up"
        VirtualButton.DOWN -> "Down"
        VirtualButton.LEFT -> "Left"
        VirtualButton.RIGHT -> "Right"
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

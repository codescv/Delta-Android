package com.codescv.delta.skins

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codescv.delta.DeltaApplication
import com.codescv.delta.model.GameSystem
import com.codescv.delta.skins.procedural.ProceduralSkin
import com.codescv.delta.skins.procedural.ProceduralSkinLayout
import com.codescv.delta.skins.procedural.RenderedSkinPreview
import com.codescv.delta.skins.procedural.SystemSkinCatalog
import com.codescv.delta.skins.procedural.VariantSkinPreview

/** One selectable controller skin for a skin family (official, built-in variant or imported). */
data class SkinOption(
    /** Stable id stored in `portraitSkinsBySystem` / `landscapeSkinsBySystem`. */
    val id: String,
    val name: String,
    val family: String,
    val kind: Kind,
    /** Short badge text (e.g. "GAME BOY ADVANCE"). */
    val badge: String = "",
) {
    enum class Kind { OFFICIAL, VARIANT, IMPORTED }

    companion object {
        const val OFFICIAL_ID = "official-standard"
    }
}

/**
 * Skin catalog per family: official `Standard <SYS>` first, then built-in procedural variants
 * (`SystemSkinCatalog` port, see [ProceduralSkins]), then imported `.deltaskin`s.
 */
object SkinCatalog {
    /** Families configurable in Settings › Skins, in Delta order. */
    val configurableFamilies = listOf("nes", "snes", "n64", "gbc", "gba", "nds", "genesis")

    fun familyDisplayName(family: String): String = when (family) {
        "nes" -> "Nintendo (NES)"
        "snes" -> "Super Nintendo"
        "n64" -> "Nintendo 64"
        "gbc" -> "Game Boy Color"
        "gba" -> "Game Boy Advance"
        "nds" -> "Nintendo DS"
        "genesis" -> "Sega Genesis"
        "chip8" -> "CHIP-8"
        else -> family.uppercase()
    }

    fun familyShortName(family: String): String = when (family) {
        "nes" -> "NES"; "snes" -> "SNES"; "n64" -> "N64"; "gbc" -> "GBC"; "gba" -> "GBA"; "nds" -> "DS"; "genesis" -> "MD"
        "chip8" -> "CHIP-8"
        else -> family.uppercase()
    }

    fun systemFor(family: String): GameSystem = when (family) {
        "nes" -> GameSystem.NES
        "snes" -> GameSystem.SNES
        "n64" -> GameSystem.N64
        "gbc" -> GameSystem.GBC
        "nds" -> GameSystem.NDS
        "genesis" -> GameSystem.GENESIS
        "chip8" -> GameSystem.CHIP8
        else -> GameSystem.GBA
    }

    fun options(family: String): List<SkinOption> {
        val repo = DeltaApplication.container.skins
        val official = repo.bundled(family)?.let {
            listOf(SkinOption(SkinOption.OFFICIAL_ID, it.skin.name, family, SkinOption.Kind.OFFICIAL))
        } ?: emptyList()
        val imported = repo.imported(family).map {
            SkinOption(it.source.id, it.skin.name, family, SkinOption.Kind.IMPORTED)
        }
        return official + ProceduralSkins.options(family) + imported
    }

    fun option(family: String, id: String?): SkinOption =
        options(family).firstOrNull { it.id == id } ?: options(family).first()
}

/**
 * Built-in procedural skin variants: Delta's `SystemSkinCatalog` (palettes, badges, ids exactly
 * as iOS, e.g. `gba-atomicPurple`, `nes-famicom`) drawn by [com.codescv.delta.skins.procedural.ProceduralSkinArtwork].
 */
object ProceduralSkins {
    /** Skin family of a system (`gb`→`gbc`, `sms`/`gg`→`genesis`, CHIP-8 → `chip8`). */
    fun familyOf(system: GameSystem): String = system.skinSystem ?: "chip8"

    /** All built-in variants of a family, default first. */
    fun options(family: String): List<SkinOption> = SystemSkinCatalog.skins(family).map {
        SkinOption(it.id, it.displayName, family, SkinOption.Kind.VARIANT, it.badge)
    }

    /** Whether [optionId] names a built-in variant of [family]. */
    fun isVariant(optionId: String?, family: String): Boolean = SystemSkinCatalog.skin(optionId, family) != null

    /** The built-in skin [optionId] for [system]'s family, falling back to the family default. */
    fun skin(optionId: String?, system: GameSystem): ProceduralSkin? {
        val family = familyOf(system)
        return SystemSkinCatalog.skin(optionId, family) ?: SystemSkinCatalog.defaultSkin(family)
    }

    /**
     * Lays out the procedural skin [optionId] (falling back to the family's default variant when
     * the id isn't a built-in variant, e.g. CHIP-8 which has no official skin) in a
     * [width] × [height] pixel container. Port of `ViewportLayout` + `ConsoleControlsLayout`:
     * portrait = painted console shell with the game at the top; landscape = game aspect-fit
     * full height with translucent controls on the sides.
     *
     * @param sourceWidth core video size, used for the aspect ratio (0 → system default).
     * @param density pixels per layout point (dp); defaults to the device density.
     * @param topInset safe-area insets in pixels (status bar / cutout).
     * @param reverseScreens NDS: show the bottom screen on top (see `ProceduralSkinLayout.screenSources`).
     * @return null for an empty container.
     */
    fun layout(
        optionId: String,
        system: GameSystem,
        width: Float,
        height: Float,
        sourceWidth: Float,
        sourceHeight: Float,
        density: Float = ProceduralSkinLayout.systemDensity(),
        topInset: Float = 0f,
        bottomInset: Float = 0f,
        leftInset: Float = 0f,
        rightInset: Float = 0f,
        reverseScreens: Boolean = false,
    ): ProceduralLayout? {
        val skin = skin(optionId, system) ?: return null
        return ProceduralSkinLayout.compute(
            skin, system, width, height, sourceWidth, sourceHeight, density, topInset, bottomInset, leftInset, rightInset,
            reverseScreens,
        )
    }
}

/** Thumbnail of a skin for pickers (Settings › Skins, pause menu "Controller Skin" chips). */
@Composable
fun SkinPreview(option: SkinOption, selected: Boolean, modifier: Modifier = Modifier) {
    val variant = if (option.kind == SkinOption.Kind.VARIANT) SystemSkinCatalog.skin(option.id, option.family) else null
    if (variant != null) {
        VariantSkinPreview(variant, selected, modifier)
    } else {
        RenderedSkinPreview(option, selected, modifier)
    }
}

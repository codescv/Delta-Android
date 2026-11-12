package com.codescv.delta.skins.procedural

import com.codescv.delta.model.VirtualButton

/**
 * Port of `GBASkin.swift` `SkinColor`: a straight (non-premultiplied) ARGB color.
 * Kept free of Compose so the catalog is usable from plain JVM unit tests.
 */
@JvmInline
value class SkinColor(val argb: Long) {
    val alpha: Float get() = ((argb shr 24) and 0xFF).toFloat() / 255f
    val red: Float get() = ((argb shr 16) and 0xFF).toFloat() / 255f
    val green: Float get() = ((argb shr 8) and 0xFF).toFloat() / 255f
    val blue: Float get() = (argb and 0xFF).toFloat() / 255f

    val luminance: Float get() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

    /** Same hue scaled towards black (factor < 1), used for pressed button faces. */
    fun scaled(factor: Float): SkinColor = of(
        alpha = alpha,
        red = minOf(red * factor, 1f),
        green = minOf(green * factor, 1f),
        blue = minOf(blue * factor, 1f),
    )

    /** Legible label color (near-black or near-white) on top of this color. */
    val contrastingIcon: SkinColor get() = if (luminance > 0.55f) SkinColor(0xFF1A1A1E) else SkinColor(0xFFF7F7FA)

    companion object {
        fun of(alpha: Float, red: Float, green: Float, blue: Float): SkinColor {
            fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toLong()
            return SkinColor((c(alpha) shl 24) or (c(red) shl 16) or (c(green) shl 8) or c(blue))
        }
    }
}

/** `GBASkinPalette`: the 20 named colors of a procedural console shell. */
data class SkinPalette(
    val shellTop: SkinColor,
    val shellBottom: SkinColor,
    val shellHighlight: SkinColor,
    val shellShadow: SkinColor,
    val bezel: SkinColor,
    val bezelText: SkinColor,
    val accent: SkinColor,
    val accentSecondary: SkinColor,
    val led: SkinColor,
    val cap: SkinColor,
    val capPressed: SkinColor,
    val capIcon: SkinColor,
    val face: SkinColor,
    val facePressed: SkinColor,
    val faceIcon: SkinColor,
    val faceStroke: SkinColor,
    val active: SkinColor,
    val activeIcon: SkinColor,
    val ring: SkinColor,
    val track: SkinColor,
)

/** `GBASpeakerStyle`: speaker grille / shell art drawn on the console body. */
enum class SpeakerStyle {
    AGB_DIAGONAL, SP_DOTS, NES_HORIZONTAL, SNES_VENT, N64_PRONG, GBC_DOTS, DS_DUAL_HINGE, GENESIS_ARC, CHIP8_GRILLE
}

/**
 * `DeltaControllerSkin`: a built-in, procedurally drawn controller skin (shell colors, bezel
 * badge, console logo, speaker art and optional per-button face colors).
 */
data class ProceduralSkin(
    val id: String,
    /** Skin family (`nes`, `snes`, `n64`, `gbc`, `gba`, `nds`, `genesis`, `chip8`). */
    val family: String,
    val displayName: String,
    /** Text drawn on the screen bezel, after "DELTA · ". */
    val badge: String,
    /** Logo drawn on the console body. */
    val consoleLogo: String,
    val speakerStyle: SpeakerStyle,
    val drawsConsoleArt: Boolean = true,
    val palette: SkinPalette,
    /** Optional per-button face colors. Buttons not listed use `palette.face`. */
    val buttonFaceColors: Map<VirtualButton, SkinColor> = emptyMap(),
) {
    /** Face color for [button], falling back to the palette's face color. */
    fun faceColor(button: VirtualButton): SkinColor = buttonFaceColors[button] ?: palette.face
}

/** Port of `SystemSkinCatalog` (`GBASkin.swift:455-789`): built-in skins per family, first = default. */
object SystemSkinCatalog {
    val families = listOf("nes", "snes", "n64", "gbc", "gba", "nds", "genesis", "chip8")

    fun skins(family: String): List<ProceduralSkin> = when (family) {
        "nes" -> nes
        "snes" -> snes
        "n64" -> n64
        "gbc", "gb" -> gbc
        "gba" -> gba
        "nds" -> nds
        "chip8" -> chip8
        "genesis", "md", "sms", "gg" -> genesis
        else -> emptyList()
    }

    fun defaultSkin(family: String): ProceduralSkin? = skins(family).firstOrNull()

    fun skin(id: String?, family: String): ProceduralSkin? = skins(family).firstOrNull { it.id == id }

    /** Any built-in skin with this id, in any family. */
    fun find(id: String?): ProceduralSkin? = families.firstNotNullOfOrNull { f -> skins(f).firstOrNull { it.id == id } }

    @Suppress("LongParameterList")
    private fun pal(
        top: Long, bottom: Long, hi: Long, shadow: Long,
        bezel: Long, bezelText: Long, accent: Long, accent2: Long,
        led: Long, cap: Long, capP: Long, capIcon: Long,
        face: Long, faceP: Long, faceIcon: Long, faceStroke: Long,
        active: Long, activeIcon: Long, ring: Long, track: Long,
    ) = SkinPalette(
        SkinColor(top), SkinColor(bottom), SkinColor(hi), SkinColor(shadow),
        SkinColor(bezel), SkinColor(bezelText), SkinColor(accent), SkinColor(accent2),
        SkinColor(led), SkinColor(cap), SkinColor(capP), SkinColor(capIcon),
        SkinColor(face), SkinColor(faceP), SkinColor(faceIcon), SkinColor(faceStroke),
        SkinColor(active), SkinColor(activeIcon), SkinColor(ring), SkinColor(track),
    )

    // MARK: Game Boy Advance (GBASkin)
    private fun gbaSkin(raw: String, displayName: String, palette: SkinPalette) = ProceduralSkin(
        id = "gba-$raw", family = "gba", displayName = displayName,
        badge = if (raw == "platinumSP") "GBA SP" else "GAME BOY ADVANCE", consoleLogo = "GAME BOY ADVANCE",
        speakerStyle = if (raw == "platinumSP") SpeakerStyle.SP_DOTS else SpeakerStyle.AGB_DIAGONAL,
        drawsConsoleArt = raw != "classicGlass", palette = palette,
    )

    /** `GBASkin` palettes keyed by raw value. */
    val gbaPalettes: Map<String, SkinPalette> = linkedMapOf(
        "classicGlass" to pal(
            0xFF2A2D34, 0xFF16181D, 0x59FFFFFF, 0x33000000,
            0xFF0B0C0F, 0xB3FFFFFF, 0x80FFFFFF, 0x4DFFFFFF,
            0xFF39FF6A, 0x80FFFFFF, 0x80000000, 0xCC000000,
            0x80FFFFFF, 0x80000000, 0xCC000000, 0x33FFFFFF,
            0xBFFF9900, 0xE6FFFFFF, 0x1AFFFFFF, 0x20FFFFFF,
        ),
        "indigo" to pal(
            0xFF5B4B9E, 0xFF41337A, 0xFF7D6EC4, 0xFF261D52,
            0xFF1C1A2B, 0xFFB9B3DE, 0xFF9A8FE0, 0xFFCBC5F0,
            0xFF3DFF6E, 0xFFDCE0EA, 0xFFA3A9BD, 0xFF2A2C35,
            0xFFDCE0EA, 0xFFA3A9BD, 0xFF2A2C35, 0x66261D52,
            0xFFFFB238, 0xFF2A1700, 0xFF34286C, 0xFFC4C9D8,
        ),
        "glacierClear" to pal(
            0xD9AEE0F2, 0xCC6FB6D6, 0xFFE8F8FF, 0xFF3A86A8,
            0xFF1C3340, 0xFFBDEEFF, 0xFF22B8C2, 0xFFE6FBFF,
            0xFF3DFF6E, 0xFFF0F9FD, 0xFFB4D6E5, 0xFF1D4A5E,
            0xFFF0F9FD, 0xFFB4D6E5, 0xFF0B5560, 0xCC22B8C2,
            0xFFFF7A45, 0xFF2B0E00, 0xFF4F9EC2, 0xFFD3ECF6,
        ),
        "atomicPurple" to pal(
            0xE68A2BE2, 0xE6571D99, 0xFFBBA0E4, 0xFF34225A,
            0xFF1A1426, 0xFFD7C6F5, 0xFFB69CFF, 0xFF4FE3D0,
            0xFF3DFF6E, 0xFF2E2B33, 0xFF151318, 0xFFDCD0F7,
            0xFFA48FD0, 0xFF6A5696, 0xFF1E1430, 0xCC4FE3D0,
            0xFF4FE3D0, 0xFF062A26, 0xFF47306B, 0xFF3A3542,
        ),
        "spiceOrange" to pal(
            0xFFF28C38, 0xFFD9661A, 0xFFFFB37A, 0xFF9C4310,
            0xFF2B1A12, 0xFFFFD9B8, 0xFFFFE0C2, 0xFF5A2A0E,
            0xFF3DFF6E, 0xFF2E2622, 0xFF15110F, 0xFFFFD2A8,
            0xFF3A2E28, 0xFF1C1612, 0xFFFFC48A, 0x99FFE0C2,
            0xFF3DD6FF, 0xFF00222B, 0xFFB0520F, 0xFF4A3A32,
        ),
        "matteMidnight" to pal(
            0xFF15151C, 0xFF050508, 0xFF262633, 0xFF000000,
            0xFF000000, 0xFF5A6BA8, 0xFF2B3F8F, 0xFF1FA3B8,
            0xFF1E9A4A, 0xFF121218, 0xFF24367A, 0xFF8490BD,
            0xFF121218, 0xFF24367A, 0xFF8C9AD0, 0x992B3F8F,
            0xFF1FA3B8, 0xFF000000, 0xFF07070B, 0xFF0E0E14,
        ),
        "platinumSP" to pal(
            0xFFE4E7EB, 0xFFA6ABB4, 0xFFF8F9FB, 0xFF6E747E,
            0xFF141619, 0xFFC9CDD3, 0xFF5A626E, 0xFFFFFFFF,
            0xFF3DFF6E, 0xFF3A3F47, 0xFF1E2126, 0xFFE9ECF0,
            0xFF3A3F47, 0xFF1E2126, 0xFFF2F4F7, 0x99FFFFFF,
            0xFF3FA9F5, 0xFF041320, 0xFF8A9099, 0xFF4A5059,
        ),
        "famicom" to pal(
            0xFFF3EAD3, 0xFFE0D0A8, 0xFFFFF9E8, 0xFFAF9B6C,
            0xFF2A1D1A, 0xFFE6D29A, 0xFF8E1B26, 0xFFC9A548,
            0xFF3DFF6E, 0xFF34312F, 0xFF181615, 0xFFF3EAD3,
            0xFF9E1C2A, 0xFF620F18, 0xFFF8EACB, 0xFFC9A548,
            0xFFFFB300, 0xFF2A1A00, 0xFFCDBA8A, 0xFF4A4542,
        ),
        "emerald" to pal(
            0xFF1F8E63, 0xFF0D573C, 0xFF3FBD88, 0xFF063524,
            0xFF0A1E16, 0xFFF2C94C, 0xFFF2C230, 0xFFFFE48A,
            0xFF7CFF9B, 0xFF1F2B26, 0xFF0E1512, 0xFFF2D46A,
            0xFF26362F, 0xFF111A16, 0xFFFFD54F, 0xFFF2C230,
            0xFF3DE0FF, 0xFF002A33, 0xFF0A4631, 0xFF2A3832,
        ),
    )

    val gbaDisplayNames: Map<String, String> = linkedMapOf("classicGlass" to "Classic Glass", "indigo" to "Indigo", "glacierClear" to "Glacier Clear", "atomicPurple" to "Atomic Purple", "spiceOrange" to "Spice Orange", "matteMidnight" to "Matte Midnight", "platinumSP" to "Platinum SP", "famicom" to "Famicom", "emerald" to "Emerald")

    val gba: List<ProceduralSkin> = listOf("atomicPurple", "indigo", "glacierClear", "spiceOrange", "platinumSP", "famicom", "emerald", "matteMidnight", "classicGlass").map { gbaSkin(it, gbaDisplayNames.getValue(it), gbaPalettes.getValue(it)) }

    private val n64Buttons = mapOf(VirtualButton.A to SkinColor(0xFF1F4FC4), VirtualButton.B to SkinColor(0xFF009B48), VirtualButton.X to SkinColor(0xFFFFC72C), VirtualButton.Y to SkinColor(0xFFFFC72C))

    val nes: List<ProceduralSkin> = listOf(
        ProceduralSkin(
            id = "nes-standard", family = "nes", displayName = "Standard NES",
            badge = "NINTENDO ENTERTAINMENT SYSTEM", consoleLogo = "NINTENDO", speakerStyle = SpeakerStyle.NES_HORIZONTAL,
            palette = pal(
                    0xFFCFCFCF, 0xFFA9A9AB, 0xFFEDEDED, 0xFF6B6B6E,
                    0xFF141414, 0xFFD9D9D9, 0xFFC8102E, 0xFFD4A82A,
                    0xFFFF3030, 0xFF232325, 0xFF0E0E0F, 0xFFBDBDBD,
                    0xFFC8102E, 0xFF7E0A1D, 0xFFFFFFFF, 0xFF2A2A2A,
                    0xFFFFB400, 0xFF2A1A00, 0xFF8E8E92, 0xFF3A3A3C,
                ),
            buttonFaceColors = mapOf(VirtualButton.A to SkinColor(0xFFC8102E), VirtualButton.B to SkinColor(0xFFC8102E)),
        ),
        ProceduralSkin(
            id = "nes-famicom", family = "nes", displayName = "Famicom Red & Gold",
            badge = "FAMILY COMPUTER", consoleLogo = "FAMICOM", speakerStyle = SpeakerStyle.NES_HORIZONTAL,
            palette = pal(
                    0xFFF1E6CC, 0xFFDCCBA2, 0xFFFFF7E6, 0xFFA48F62,
                    0xFF5A1620, 0xFFE8CF8A, 0xFF8E1B26, 0xFFC9A548,
                    0xFFFF3B3B, 0xFF6B1A22, 0xFF3E0E13, 0xFFF1E6CC,
                    0xFF8E1B26, 0xFF56101A, 0xFFF8EACB, 0xFFC9A548,
                    0xFFFFC23D, 0xFF2A1A00, 0xFFB9A57A, 0xFF4E2328,
                ),
            buttonFaceColors = mapOf(VirtualButton.A to SkinColor(0xFF8E1B26), VirtualButton.B to SkinColor(0xFF8E1B26)),
        ),
        ProceduralSkin(
            id = "nes-midnight", family = "nes", displayName = "NES Midnight",
            badge = "NES MIDNIGHT", consoleLogo = "NINTENDO", speakerStyle = SpeakerStyle.NES_HORIZONTAL,
            palette = pal(
                    0xFF2A2A2E, 0xFF141416, 0xFF3C3C42, 0xFF000000,
                    0xFF060607, 0xFFE0475B, 0xFFB3122E, 0xFF6E6E74,
                    0xFFFF2945, 0xFF1A1A1D, 0xFF0A0A0B, 0xFFE0475B,
                    0xFFB3122E, 0xFF6A0A1B, 0xFFFFFFFF, 0xFF4A4A50,
                    0xFFFF6B81, 0xFF2B0008, 0xFF101012, 0xFF222226,
                ),
            buttonFaceColors = mapOf(VirtualButton.A to SkinColor(0xFFB3122E), VirtualButton.B to SkinColor(0xFFB3122E)),
        ),
    )

    val snes: List<ProceduralSkin> = listOf(
        ProceduralSkin(
            id = "snes-standard", family = "snes", displayName = "Standard SNES (US)",
            badge = "SUPER NINTENDO", consoleLogo = "SUPER NINTENDO", speakerStyle = SpeakerStyle.SNES_VENT,
            palette = pal(
                    0xFFD5D2CC, 0xFFB4B0A8, 0xFFEEECE7, 0xFF77736B,
                    0xFF2B2B30, 0xFFC9C3E6, 0xFF5B3F9E, 0xFFB9AEE0,
                    0xFFFF3B3B, 0xFF4A4A50, 0xFF2A2A2E, 0xFFE6E4DF,
                    0xFF5B3F9E, 0xFF3A2766, 0xFFFFFFFF, 0xFF8D8A84,
                    0xFFFFB238, 0xFF2A1700, 0xFF9C988F, 0xFF8A8780,
                ),
            buttonFaceColors = mapOf(VirtualButton.A to SkinColor(0xFF5B3F9E), VirtualButton.B to SkinColor(0xFF5B3F9E), VirtualButton.X to SkinColor(0xFFB9AEE0), VirtualButton.Y to SkinColor(0xFFB9AEE0)),
        ),
        ProceduralSkin(
            id = "snes-super-famicom", family = "snes", displayName = "Super Famicom (4-Color)",
            badge = "SUPER FAMICOM", consoleLogo = "SUPER FAMICOM", speakerStyle = SpeakerStyle.SNES_VENT,
            palette = pal(
                    0xFFE3E1DD, 0xFFC4C1BB, 0xFFF6F5F2, 0xFF85827C,
                    0xFF333338, 0xFFE6E3DE, 0xFFD7263D, 0xFF2B59C3,
                    0xFFFF3B3B, 0xFF55555B, 0xFF2E2E33, 0xFFEDEBE7,
                    0xFFD7263D, 0xFF861525, 0xFFFFFFFF, 0xFF9A978F,
                    0xFF8A2BE2, 0xFFFFFFFF, 0xFFAAA7A0, 0xFF96938C,
                ),
            buttonFaceColors = mapOf(VirtualButton.A to SkinColor(0xFFD7263D), VirtualButton.B to SkinColor(0xFFF2C12E), VirtualButton.X to SkinColor(0xFF2B59C3), VirtualButton.Y to SkinColor(0xFF2E9E4F)),
        ),
        ProceduralSkin(
            id = "snes-atomic-purple", family = "snes", displayName = "Atomic Purple SNES",
            badge = "SNES ATOMIC PURPLE", consoleLogo = "SUPER NINTENDO", speakerStyle = SpeakerStyle.SNES_VENT,
            palette = pal(
                    0xE6772ACC, 0xE64A1A80, 0xFFB79AE8, 0xFF2E1D52,
                    0xFF181224, 0xFFD9C8F7, 0xFFB69CFF, 0xFF4FE3D0,
                    0xFF3DFF6E, 0xFF2E2A36, 0xFF14121A, 0xFFDCD0F7,
                    0xFF8C6CD6, 0xFF5A4496, 0xFF1A1030, 0xCC4FE3D0,
                    0xFF4FE3D0, 0xFF062A26, 0xFF3F2A63, 0xFF36303F,
                ),
            buttonFaceColors = mapOf(VirtualButton.A to SkinColor(0xFF6A4BC4), VirtualButton.B to SkinColor(0xFF6A4BC4), VirtualButton.X to SkinColor(0xFFC4B5F2), VirtualButton.Y to SkinColor(0xFFC4B5F2)),
        ),
    )

    val n64: List<ProceduralSkin> = listOf(
        ProceduralSkin(
            id = "n64-charcoal", family = "n64", displayName = "Standard Charcoal N64",
            badge = "NINTENDO 64", consoleLogo = "NINTENDO 64", speakerStyle = SpeakerStyle.N64_PRONG,
            palette = pal(
                    0xFF4A4A4F, 0xFF2E2E32, 0xFF66666C, 0xFF151517,
                    0xFF0F0F11, 0xFFBFBFC5, 0xFFE4002B, 0xFF009B48,
                    0xFFFF2A2A, 0xFF3A3A3F, 0xFF1C1C1F, 0xFFD6D6DB,
                    0xFF1F4FC4, 0xFF12307A, 0xFFFFFFFF, 0xFF5A5A60,
                    0xFFFFD100, 0xFF2A2200, 0xFF242427, 0xFF55555B,
                ),
            buttonFaceColors = n64Buttons,
        ),
        ProceduralSkin(
            id = "n64-funtastic-grape", family = "n64", displayName = "Funtastic Grape Purple",
            badge = "NINTENDO 64", consoleLogo = "NINTENDO 64", speakerStyle = SpeakerStyle.N64_PRONG,
            palette = pal(
                    0xD97A3FB8, 0xD9542A85, 0xFFA98BD8, 0xFF2D1A4A,
                    0xFF1A1226, 0xFFD4C2F2, 0xFFC7A8FF, 0xFFFFC72C,
                    0xFFFF3A3A, 0xFF4B2F78, 0xFF2C1A4A, 0xFFE8DCFF,
                    0xFF1F4FC4, 0xFF12307A, 0xFFFFFFFF, 0x99E8DCFF,
                    0xFF4FE3D0, 0xFF062A26, 0xFF3A2460, 0xFF4E3878,
                ),
            buttonFaceColors = n64Buttons,
        ),
        ProceduralSkin(
            id = "n64-jungle-green", family = "n64", displayName = "Funtastic Jungle Green",
            badge = "NINTENDO 64", consoleLogo = "NINTENDO 64", speakerStyle = SpeakerStyle.N64_PRONG,
            palette = pal(
                    0xD9379A4E, 0xD91F6B33, 0xFF7FD394, 0xFF0E3A1A,
                    0xFF0C1E12, 0xFFBDF2C9, 0xFF9FE8B0, 0xFFFFC72C,
                    0xFFFF3A3A, 0xFF1F5A2E, 0xFF103A1C, 0xFFDFF7E5,
                    0xFF1F4FC4, 0xFF12307A, 0xFFFFFFFF, 0x99DFF7E5,
                    0xFFFF7A45, 0xFF2B0E00, 0xFF175029, 0xFF2C6E3E,
                ),
            buttonFaceColors = n64Buttons,
        ),
        ProceduralSkin(
            id = "n64-ice-blue", family = "n64", displayName = "Funtastic Ice Blue",
            badge = "NINTENDO 64", consoleLogo = "NINTENDO 64", speakerStyle = SpeakerStyle.N64_PRONG,
            palette = pal(
                    0xD98FC9EE, 0xD95A9ED0, 0xFFDDF1FF, 0xFF2C6A94,
                    0xFF14283A, 0xFFCFEBFF, 0xFF1E88E5, 0xFFFFC72C,
                    0xFFFF3A3A, 0xFF4C8DBE, 0xFF2F6590, 0xFFF2FAFF,
                    0xFF1F4FC4, 0xFF12307A, 0xFFFFFFFF, 0x99F2FAFF,
                    0xFFFF5FA2, 0xFF2B0016, 0xFF3F7FAE, 0xFF6FA9D3,
                ),
            buttonFaceColors = n64Buttons,
        ),
    )

    val gbc: List<ProceduralSkin> = listOf(
        ProceduralSkin(
            id = "gbc-grape", family = "gbc", displayName = "Grape Purple GBC",
            badge = "GAME BOY COLOR", consoleLogo = "GAME BOY COLOR", speakerStyle = SpeakerStyle.GBC_DOTS,
            palette = pal(
                    0xFF6B3FA0, 0xFF4B2A78, 0xFF8E66C4, 0xFF2A1748,
                    0xFF2B2D3A, 0xFFC9C3E6, 0xFFE9E3F7, 0xFFFF4F6D,
                    0xFFFF3A3A, 0xFF2B2B30, 0xFF141417, 0xFFCFCFD6,
                    0xFF2B2B30, 0xFF141417, 0xFFE6E6EC, 0x66E9E3F7,
                    0xFFFFC72C, 0xFF2A2000, 0xFF3B2264, 0xFF3A3A40,
                ),
        ),
        ProceduralSkin(
            id = "gbc-atomic-purple", family = "gbc", displayName = "Atomic Purple GBC",
            badge = "GAME BOY COLOR", consoleLogo = "GAME BOY COLOR", speakerStyle = SpeakerStyle.GBC_DOTS,
            palette = pal(
                    0xE6863ED6, 0xE65A2299, 0xFFC4A6EE, 0xFF331F57,
                    0xFF1B1428, 0xFFDCCBF7, 0xFFC3ABFF, 0xFF4FE3D0,
                    0xFFFF3A3A, 0xFF35303D, 0xFF18151C, 0xFFE0D4F9,
                    0xFF35303D, 0xFF18151C, 0xFFE0D4F9, 0xCC4FE3D0,
                    0xFF4FE3D0, 0xFF062A26, 0xFF4A3270, 0xFF3E3846,
                ),
        ),
        ProceduralSkin(
            id = "gbc-teal", family = "gbc", displayName = "Teal GBC",
            badge = "GAME BOY COLOR", consoleLogo = "GAME BOY COLOR", speakerStyle = SpeakerStyle.GBC_DOTS,
            palette = pal(
                    0xFF14A3A8, 0xFF0B7A80, 0xFF5FD0D3, 0xFF054B4F,
                    0xFF20303A, 0xFFBDEFF0, 0xFFE6FAFA, 0xFFFFC72C,
                    0xFFFF3A3A, 0xFF22343A, 0xFF101A1D, 0xFFD2F2F3,
                    0xFF22343A, 0xFF101A1D, 0xFFD2F2F3, 0x66E6FAFA,
                    0xFFFF7A45, 0xFF2B0E00, 0xFF086166, 0xFF2E4248,
                ),
        ),
        ProceduralSkin(
            id = "gbc-dandelion", family = "gbc", displayName = "Dandelion Yellow GBC",
            badge = "GAME BOY COLOR", consoleLogo = "GAME BOY COLOR", speakerStyle = SpeakerStyle.GBC_DOTS,
            palette = pal(
                    0xFFF7D23E, 0xFFE0B21C, 0xFFFFE98A, 0xFF9C7A08,
                    0xFF2E2C33, 0xFFFFF2B8, 0xFF4A3B00, 0xFF1E88E5,
                    0xFFFF3A3A, 0xFF2E2C33, 0xFF161519, 0xFFFFE98A,
                    0xFF2E2C33, 0xFF161519, 0xFFFFE98A, 0x664A3B00,
                    0xFF1E88E5, 0xFFFFFFFF, 0xFFB8920E, 0xFF3C3A42,
                ),
        ),
        ProceduralSkin(
            id = "gbc-berry", family = "gbc", displayName = "Berry Pink GBC",
            badge = "GAME BOY COLOR", consoleLogo = "GAME BOY COLOR", speakerStyle = SpeakerStyle.GBC_DOTS,
            palette = pal(
                    0xFFD8325E, 0xFFAE1E46, 0xFFF07A9A, 0xFF6E0F2A,
                    0xFF2E2230, 0xFFFFD0DC, 0xFFFFE3EA, 0xFFFFC72C,
                    0xFF3DFF6E, 0xFF2E2A30, 0xFF151317, 0xFFFFD0DC,
                    0xFF2E2A30, 0xFF151317, 0xFFFFD0DC, 0x66FFE3EA,
                    0xFF3DD6FF, 0xFF00222B, 0xFF8E1739, 0xFF3E363F,
                ),
        ),
        ProceduralSkin(
            id = "gb-dmg", family = "gbc", displayName = "Original DMG-01 Grey",
            badge = "GAME BOY DMG-01", consoleLogo = "GAME BOY", speakerStyle = SpeakerStyle.AGB_DIAGONAL,
            palette = pal(
                    0xFFC9C6BD, 0xFFB3AFA4, 0xFFE4E1D9, 0xFF7D796F,
                    0xFF5C6340, 0xFFC8D08A, 0xFF2B2E7E, 0xFF8B1E4F,
                    0xFFFF2A2A, 0xFF2B2B2E, 0xFF151517, 0xFFB3AFA4,
                    0xFF8B1E4F, 0xFF56102F, 0xFFF2E6EC, 0xFF7D796F,
                    0xFF9BBC0F, 0xFF0F380F, 0xFF9E9A90, 0xFF8E8A80,
                ),
            buttonFaceColors = mapOf(VirtualButton.A to SkinColor(0xFF8B1E4F), VirtualButton.B to SkinColor(0xFF8B1E4F)),
        ),
    )

    val nds: List<ProceduralSkin> = listOf(
        ProceduralSkin(
            id = "nds-pink", family = "nds", displayName = "Standard DS Rose Pink",
            badge = "NINTENDO DS", consoleLogo = "NINTENDO DS", speakerStyle = SpeakerStyle.DS_DUAL_HINGE,
            palette = pal(
                    0xFFF2B6C6, 0xFFE08FA6, 0xFFFFDDE6, 0xFFA85A70,
                    0xFF1C1A1E, 0xFFFFD6E2, 0xFF8A3F55, 0xFFFFFFFF,
                    0xFF3DFF6E, 0xFF3A3438, 0xFF1D1A1C, 0xFFF8E3EA,
                    0xFFF7EDF0, 0xFFCFB9C0, 0xFF5A3A44, 0x998A3F55,
                    0xFF3DB8FF, 0xFF001C2B, 0xFFC97890, 0xFF4A4247,
                ),
        ),
        ProceduralSkin(
            id = "nds-polar-white", family = "nds", displayName = "DS Lite Polar White",
            badge = "NINTENDO DS LITE", consoleLogo = "NINTENDO DS", speakerStyle = SpeakerStyle.DS_DUAL_HINGE,
            palette = pal(
                    0xFFF7F7F9, 0xFFDCDDE2, 0xFFFFFFFF, 0xFF9EA1A8,
                    0xFF16171A, 0xFFD8DADF, 0xFF6E737C, 0xFF3FA9F5,
                    0xFF3DFF6E, 0xFFE6E7EB, 0xFFB8BAC1, 0xFF3A3D44,
                    0xFFFFFFFF, 0xFFC9CBD1, 0xFF3A3D44, 0xFFB8BAC1,
                    0xFF3FA9F5, 0xFFFFFFFF, 0xFFC4C6CC, 0xFFD0D2D8,
                ),
        ),
        ProceduralSkin(
            id = "nds-cobalt-black", family = "nds", displayName = "DS Lite Cobalt & Black",
            badge = "NINTENDO DS LITE", consoleLogo = "NINTENDO DS", speakerStyle = SpeakerStyle.DS_DUAL_HINGE,
            palette = pal(
                    0xFF2A4FA8, 0xFF111317, 0xFF5576C9, 0xFF070809,
                    0xFF050607, 0xFF8FB0FF, 0xFF4F7BE8, 0xFFB9C9F2,
                    0xFF3DFF6E, 0xFF1A1C21, 0xFF0B0C0E, 0xFF9DB7F5,
                    0xFF1A1C21, 0xFF0B0C0E, 0xFFB9C9F2, 0x994F7BE8,
                    0xFF4F7BE8, 0xFFFFFFFF, 0xFF14161A, 0xFF22252B,
                ),
        ),
        ProceduralSkin(
            id = "nds-dsi-matte", family = "nds", displayName = "DSi Matte Midnight",
            badge = "NINTENDO DSi", consoleLogo = "NINTENDO DSi", speakerStyle = SpeakerStyle.DS_DUAL_HINGE,
            palette = pal(
                    0xFF26272B, 0xFF141518, 0xFF393A40, 0xFF000000,
                    0xFF0A0A0C, 0xFF8E9099, 0xFF5C5F68, 0xFF2FD3FF,
                    0xFF2FD3FF, 0xFF1C1D21, 0xFF0D0D10, 0xFFA5A8B2,
                    0xFF1C1D21, 0xFF0D0D10, 0xFFC2C5CE, 0x665C5F68,
                    0xFF2FD3FF, 0xFF00222B, 0xFF101114, 0xFF1E1F23,
                ),
        ),
    )

    val genesis: List<ProceduralSkin> = listOf(
        ProceduralSkin(
            id = "genesis-standard", family = "genesis", displayName = "Standard Genesis 16-Bit",
            badge = "SEGA GENESIS · 16-BIT", consoleLogo = "SEGA 16-BIT", speakerStyle = SpeakerStyle.GENESIS_ARC,
            palette = pal(
                    0xFF1E1E21, 0xFF0B0B0C, 0xFF34343A, 0xFF000000,
                    0xFF050505, 0xFFE6E6E6, 0xFFD3222A, 0xFFC0C2C7,
                    0xFFFF2A2A, 0xFF2A2A2E, 0xFF121214, 0xFFC0C2C7,
                    0xFFC0C2C7, 0xFF8A8C91, 0xFF111113, 0xFF4A4A50,
                    0xFFD3222A, 0xFFFFFFFF, 0xFF141416, 0xFF2A2A2E,
                ),
            buttonFaceColors = mapOf(VirtualButton.Y to SkinColor(0xFFD9DBDF), VirtualButton.B to SkinColor(0xFFC0C2C7), VirtualButton.A to SkinColor(0xFFD9DBDF)),
        ),
        ProceduralSkin(
            id = "genesis-megadrive-jp", family = "genesis", displayName = "Mega Drive JP (Gold)",
            badge = "MEGA DRIVE · 16-BIT", consoleLogo = "MEGA DRIVE", speakerStyle = SpeakerStyle.GENESIS_ARC,
            palette = pal(
                    0xFF202022, 0xFF0E0E0F, 0xFF38383C, 0xFF000000,
                    0xFF070707, 0xFFE8C766, 0xFFD4AF37, 0xFFB8202A,
                    0xFFFF2A2A, 0xFF2B2A28, 0xFF131211, 0xFFE8C766,
                    0xFF2B2A28, 0xFF131211, 0xFFE8C766, 0xFFD4AF37,
                    0xFFD4AF37, 0xFF1A1400, 0xFF151413, 0xFF2E2C29,
                ),
        ),
        ProceduralSkin(
            id = "genesis-nomad", family = "genesis", displayName = "Sega Nomad Handheld",
            badge = "SEGA NOMAD", consoleLogo = "SEGA NOMAD", speakerStyle = SpeakerStyle.GENESIS_ARC,
            palette = pal(
                    0xFF3A3F4A, 0xFF232730, 0xFF555B68, 0xFF0E1015,
                    0xFF0C0D11, 0xFFB8BFCC, 0xFF5A88D6, 0xFFD3222A,
                    0xFF3DFF6E, 0xFF1E2128, 0xFF0E1014, 0xFFB8BFCC,
                    0xFF1E2128, 0xFF0E1014, 0xFFDCE2EC, 0x995A88D6,
                    0xFF5A88D6, 0xFFFFFFFF, 0xFF181B22, 0xFF2C3039,
                ),
        ),
    )

    val chip8: List<ProceduralSkin> = listOf(
        ProceduralSkin(
            id = "chip8-cosmac", family = "chip8", displayName = "COSMAC VIP Terminal",
            badge = "COSMAC VIP · CHIP-8", consoleLogo = "COSMAC VIP", speakerStyle = SpeakerStyle.CHIP8_GRILLE,
            palette = pal(
                    0xFF3B3A36, 0xFF262522, 0xFF55534D, 0xFF12110F,
                    0xFF050805, 0xFF39FF6A, 0xFF39FF6A, 0xFFC9C3B0,
                    0xFF39FF6A, 0xFFD8D2BE, 0xFFA59F8B, 0xFF1E1D1A,
                    0xFFD8D2BE, 0xFFA59F8B, 0xFF1E1D1A, 0xFF6E6A5E,
                    0xFF39FF6A, 0xFF00230A, 0xFF2E2D29, 0xFF4A4843,
                ),
        ),
        ProceduralSkin(
            id = "chip8-amber", family = "chip8", displayName = "Amber Phosphor CRT",
            badge = "COSMAC VIP · CHIP-8", consoleLogo = "COSMAC VIP", speakerStyle = SpeakerStyle.CHIP8_GRILLE,
            palette = pal(
                    0xFF2B2219, 0xFF17120C, 0xFF453726, 0xFF080604,
                    0xFF0A0703, 0xFFFFB000, 0xFFFFB000, 0xFFFFD27A,
                    0xFFFFB000, 0xFF3A2E20, 0xFF1E170F, 0xFFFFC447,
                    0xFF3A2E20, 0xFF1E170F, 0xFFFFC447, 0x99FFB000,
                    0xFFFFB000, 0xFF2A1A00, 0xFF201910, 0xFF33291C,
                ),
        ),
    )
}

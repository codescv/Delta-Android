package com.codescv.delta.model

/** Libretro RetroPad buttons (Delta `VirtualButton`). */
enum class VirtualButton(val retroId: Int) {
    UP(4), DOWN(5), LEFT(6), RIGHT(7),
    A(8), B(0), X(9), Y(1),
    L(10), R(11), L2(12), R2(13),
    START(3), SELECT(2);

    val mask: Int get() = 1 shl retroId

    companion object {
        val DPAD: Set<VirtualButton> = setOf(UP, DOWN, LEFT, RIGHT)
        fun mask(buttons: Collection<VirtualButton>): Int = buttons.fold(0) { acc, b -> acc or b.mask }
        fun buttons(mask: Int): Set<VirtualButton> = entries.filter { mask and it.mask != 0 }.toSet()
        fun fromName(name: String): VirtualButton? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Delta's `DPadGeometry`: 8-way sector mapping with a small center dead zone. */
object DPadGeometry {
    const val DEFAULT_DEAD_ZONE = 0.10f

    /**
     * Directions for a touch at ([x], [y]) in D-Pad local coordinates of a [width] x [height] pad.
     * When [clampToRadius] is false (touch locked onto the D-Pad) sliding past the ring keeps
     * the direction held.
     */
    fun directions(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        deadZone: Float = DEFAULT_DEAD_ZONE,
        clampToRadius: Boolean = true,
    ): Set<VirtualButton> {
        val radius = minOf(width, height) / 2f
        if (radius <= 0f) return emptySet()
        val dx = x - width / 2f
        val dy = y - height / 2f
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        if (distance < radius * deadZone) return emptySet()
        if (clampToRadius && distance > radius) return emptySet()
        var degrees = Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), dx.toDouble()))
        if (degrees < 0) degrees += 360.0
        val sector = (((degrees + 22.5) % 360.0) / 45.0).toInt()
        return when (sector) {
            0 -> setOf(VirtualButton.RIGHT)
            1 -> setOf(VirtualButton.UP, VirtualButton.RIGHT)
            2 -> setOf(VirtualButton.UP)
            3 -> setOf(VirtualButton.UP, VirtualButton.LEFT)
            4 -> setOf(VirtualButton.LEFT)
            5 -> setOf(VirtualButton.DOWN, VirtualButton.LEFT)
            6 -> setOf(VirtualButton.DOWN)
            else -> setOf(VirtualButton.DOWN, VirtualButton.RIGHT)
        }
    }
}

data class GamepadLayout(
    val faceButtons: List<VirtualButton>,
    val shoulderButtons: List<VirtualButton>,
    val hasStartSelect: Boolean,
    val faceLabels: Map<VirtualButton, String>,
)

/** Systems supported by Delta (NES, SNES, N64, GBC/GB, GBA, DS, Genesis + CHIP-8). */
enum class GameSystem(val id: String) {
    NES("nes"), SNES("snes"), N64("n64"), GBC("gbc"), GB("gb"), GBA("gba"), NDS("nds"),
    GENESIS("md"), MASTER_SYSTEM("sms"), GAME_GEAR("gg"), CHIP8("chip8");

    val displayName: String
        get() = when (this) {
            NES -> "Nintendo (NES)"
            SNES -> "Super Nintendo"
            N64 -> "Nintendo 64"
            GBC -> "Game Boy Color"
            GB -> "Game Boy"
            GBA -> "Game Boy Advance"
            NDS -> "Nintendo DS"
            GENESIS -> "Sega Genesis"
            MASTER_SYSTEM -> "Master System"
            GAME_GEAR -> "Game Gear"
            CHIP8 -> "CHIP-8"
        }

    val shortName: String
        get() = when (this) {
            NES -> "NES"; SNES -> "SNES"; N64 -> "N64"; GBC -> "GBC"; GB -> "GB"; GBA -> "GBA"
            NDS -> "DS"; GENESIS -> "MD"; MASTER_SYSTEM -> "SMS"; GAME_GEAR -> "GG"; CHIP8 -> "CHIP-8"
        }

    /** Library page / filter group. */
    val category: GameSystem
        get() = when (this) {
            GBC -> GB
            MASTER_SYSTEM, GAME_GEAR -> GENESIS
            else -> this
        }

    val fileExtensions: List<String>
        get() = when (this) {
            GBA -> listOf("gba")
            GB -> listOf("gb")
            GBC -> listOf("gbc")
            NES -> listOf("nes")
            SNES -> listOf("sfc", "smc")
            N64 -> listOf("n64", "z64", "v64")
            GENESIS -> listOf("md", "gen", "smd")
            MASTER_SYSTEM -> listOf("sms")
            GAME_GEAR -> listOf("gg")
            NDS -> listOf("nds", "dsi")
            CHIP8 -> listOf("ch8", "c8")
        }

    /** Statically linked core id in libdelta_core.so. */
    val coreId: String
        get() = when (this) {
            NES -> "nes"
            SNES -> "snes"
            N64 -> "n64"
            GB, GBC -> "gbc"
            GBA -> "gba"
            NDS -> "ds"
            GENESIS, MASTER_SYSTEM, GAME_GEAR -> "genesis"
            CHIP8 -> "chip8"
        }

    val coreDisplayName: String
        get() = when (this) {
            NES -> "QuickNES"
            SNES -> "Snes9x 2005"
            N64 -> "Mupen64Plus"
            GB, GBC -> "Gambatte"
            GBA -> "VBA-Next"
            NDS -> "melonDS"
            GENESIS, MASTER_SYSTEM, GAME_GEAR -> "Genesis Plus GX"
            CHIP8 -> "CHIP-8"
        }

    /** Official `.deltaskin` family bundled for this system, if any. */
    val skinSystem: String?
        get() = when (this) {
            GBA -> "gba"
            GB, GBC -> "gbc"
            NES -> "nes"
            SNES -> "snes"
            N64 -> "n64"
            NDS -> "nds"
            GENESIS, MASTER_SYSTEM, GAME_GEAR -> "genesis"
            CHIP8 -> null
        }

    val defaultAspectRatio: Double
        get() = when (this) {
            GBA -> 240.0 / 160.0
            GB, GBC, GAME_GEAR -> 160.0 / 144.0
            NDS -> 256.0 / 384.0
            CHIP8 -> 2.0
            else -> 4.0 / 3.0
        }

    val gamepadLayout: GamepadLayout
        get() = when (this) {
            GBA -> GamepadLayout(
                listOf(VirtualButton.B, VirtualButton.A), listOf(VirtualButton.L, VirtualButton.R), true,
                mapOf(VirtualButton.A to "A", VirtualButton.B to "B"),
            )
            GB, GBC, NES, MASTER_SYSTEM, GAME_GEAR -> GamepadLayout(
                listOf(VirtualButton.B, VirtualButton.A), emptyList(), true,
                mapOf(VirtualButton.A to "A", VirtualButton.B to "B"),
            )
            SNES, NDS, N64 -> GamepadLayout(
                listOf(VirtualButton.Y, VirtualButton.X, VirtualButton.B, VirtualButton.A),
                listOf(VirtualButton.L, VirtualButton.R), true,
                mapOf(VirtualButton.A to "A", VirtualButton.B to "B", VirtualButton.X to "X", VirtualButton.Y to "Y"),
            )
            GENESIS -> GamepadLayout(
                listOf(VirtualButton.Y, VirtualButton.B, VirtualButton.A), emptyList(), true,
                mapOf(VirtualButton.Y to "A", VirtualButton.B to "B", VirtualButton.A to "C"),
            )
            CHIP8 -> GamepadLayout(
                listOf(VirtualButton.B, VirtualButton.A), listOf(VirtualButton.L, VirtualButton.R), true,
                mapOf(VirtualButton.A to "5", VirtualButton.B to "0"),
            )
        }

    companion object {
        /** Library pages in Delta order. */
        val libraryPages: List<GameSystem> = listOf(NES, SNES, N64, GB, GBA, NDS, GENESIS)

        val allExtensions: Set<String> = entries.flatMap { it.fileExtensions }.toSet()

        fun fromId(id: String): GameSystem? = entries.firstOrNull { it.id == id }

        fun detect(fileExtension: String): GameSystem? {
            val lower = fileExtension.lowercase()
            return entries.firstOrNull { lower in it.fileExtensions }
        }

        fun detectFileName(name: String): GameSystem? = detect(name.substringAfterLast('.', ""))
    }
}

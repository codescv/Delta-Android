package com.codescv.delta.cheats

import com.codescv.delta.model.GameSystem
import kotlinx.serialization.Serializable
import java.util.UUID

/** Delta cheat formats (`DeltaCheatFormat`). */
@Serializable
enum class CheatFormat(val displayName: String) {
    GAME_GENIE("Game Genie"),
    ACTION_REPLAY("Pro Action Replay"),
    GAME_SHARK("GameShark"),
    CODE_BREAKER("CodeBreaker"),
    RAW("Raw");

    companion object {
        /** Formats Delta offers per system, in picker order. */
        fun supported(system: GameSystem): List<CheatFormat> = when (system.category) {
            GameSystem.NES -> listOf(GAME_GENIE)
            GameSystem.SNES -> listOf(GAME_GENIE, ACTION_REPLAY)
            GameSystem.GB -> listOf(GAME_GENIE, GAME_SHARK)
            GameSystem.GBA -> listOf(ACTION_REPLAY, GAME_SHARK, CODE_BREAKER)
            GameSystem.N64 -> listOf(GAME_SHARK)
            GameSystem.NDS -> listOf(ACTION_REPLAY)
            GameSystem.GENESIS -> listOf(GAME_GENIE, ACTION_REPLAY)
            else -> listOf(RAW)
        }
    }
}

@Serializable
data class CheatCode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val code: String,
    val format: CheatFormat = CheatFormat.GAME_SHARK,
    val isEnabled: Boolean = true,
)

/**
 * Validates and normalizes cheat codes the way Delta's cheat editor does: every line of a
 * multi-line code must match the format's pattern for the system.
 */
object CheatValidator {
    sealed interface Result {
        data class Valid(val normalized: String) : Result
        data class Invalid(val reason: String) : Result
    }

    private const val NES_GG = "APZLGITYEOXUKSVN"
    private const val SNES_GG = "DF4709156BC8A23E"
    private const val GENESIS_GG = "ABCDEFGHJKLMNPRSTVWXYZ0123456789"

    private fun hex(n: Int) = "[0-9A-F]{$n}"

    /** Per-line regex (after uppercasing and whitespace normalization). */
    fun linePattern(system: GameSystem, format: CheatFormat): Regex? = when (system.category) {
        GameSystem.NES -> if (format == CheatFormat.GAME_GENIE) Regex("[$NES_GG]{6}|[$NES_GG]{8}") else null
        GameSystem.SNES -> when (format) {
            CheatFormat.GAME_GENIE -> Regex("[$SNES_GG]{4}-[$SNES_GG]{4}")
            CheatFormat.ACTION_REPLAY -> Regex(hex(8))
            else -> null
        }
        GameSystem.GB -> when (format) {
            CheatFormat.GAME_GENIE -> Regex("${hex(3)}-${hex(3)}(-${hex(3)})?")
            CheatFormat.GAME_SHARK -> Regex(hex(8))
            else -> null
        }
        GameSystem.GBA -> when (format) {
            CheatFormat.ACTION_REPLAY, CheatFormat.GAME_SHARK -> Regex("${hex(8)} ${hex(8)}")
            CheatFormat.CODE_BREAKER -> Regex("${hex(8)} ${hex(4)}")
            else -> null
        }
        GameSystem.N64 -> if (format == CheatFormat.GAME_SHARK) Regex("${hex(8)} ${hex(4)}") else null
        GameSystem.NDS -> if (format == CheatFormat.ACTION_REPLAY) Regex("${hex(8)} ${hex(8)}") else null
        GameSystem.GENESIS -> when (format) {
            CheatFormat.GAME_GENIE -> Regex("[$GENESIS_GG]{4}-[$GENESIS_GG]{4}")
            CheatFormat.ACTION_REPLAY -> Regex("${hex(6)}:${hex(4)}")
            else -> null
        }
        else -> Regex(".+")
    }

    /** Human-readable placeholder like Delta's code field hint. */
    fun placeholder(system: GameSystem, format: CheatFormat): String = when (system.category) {
        GameSystem.NES -> "XXXXXX or XXXXXXXX"
        GameSystem.SNES -> if (format == CheatFormat.GAME_GENIE) "XXXX-XXXX" else "XXXXXXXX"
        GameSystem.GB -> if (format == CheatFormat.GAME_GENIE) "XXX-XXX-XXX" else "XXXXXXXX"
        GameSystem.GBA -> if (format == CheatFormat.CODE_BREAKER) "XXXXXXXX YYYY" else "XXXXXXXX YYYYYYYY"
        GameSystem.N64 -> "XXXXXXXX YYYY"
        GameSystem.NDS -> "XXXXXXXX YYYYYYYY"
        GameSystem.GENESIS -> if (format == CheatFormat.GAME_GENIE) "XXXX-XXXX" else "XXXXXX:YYYY"
        else -> "Code"
    }

    /** Normalizes one line: uppercase, collapse whitespace, auto-insert separators. */
    fun normalizeLine(raw: String, system: GameSystem, format: CheatFormat): String {
        var s = raw.trim().uppercase().replace(Regex("\\s+"), " ")
        val compact = s.replace(" ", "").replace("-", "").replace(":", "")
        when (system.category) {
            GameSystem.GBA, GameSystem.NDS -> {
                if (compact.length == 16 && format != CheatFormat.CODE_BREAKER) s = compact.substring(0, 8) + " " + compact.substring(8)
                if (compact.length == 12 && format == CheatFormat.CODE_BREAKER) s = compact.substring(0, 8) + " " + compact.substring(8)
            }
            GameSystem.N64 -> if (compact.length == 12) s = compact.substring(0, 8) + " " + compact.substring(8)
            GameSystem.SNES, GameSystem.GENESIS -> if (format == CheatFormat.GAME_GENIE && compact.length == 8) {
                s = compact.substring(0, 4) + "-" + compact.substring(4)
            } else if (system.category == GameSystem.GENESIS && format == CheatFormat.ACTION_REPLAY && compact.length == 10) {
                s = compact.substring(0, 6) + ":" + compact.substring(6)
            } else if (system.category == GameSystem.SNES && format == CheatFormat.ACTION_REPLAY) {
                s = compact
            }
            GameSystem.GB -> if (format == CheatFormat.GAME_GENIE && (compact.length == 6 || compact.length == 9)) {
                s = compact.chunked(3).joinToString("-")
            } else if (format == CheatFormat.GAME_SHARK) {
                s = compact
            }
            GameSystem.NES -> s = compact
            else -> Unit
        }
        return s
    }

    fun validate(code: String, system: GameSystem, format: CheatFormat): Result {
        val lines = code.split('\n', '+', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Result.Invalid("Code is empty.")
        val pattern = linePattern(system, format)
            ?: return Result.Invalid("${format.displayName} codes are not supported for ${system.displayName}.")
        val normalized = ArrayList<String>()
        for (line in lines) {
            val n = normalizeLine(line, system, format)
            if (!pattern.matches(n)) {
                return Result.Invalid("“$line” is not a valid ${format.displayName} code (expected ${placeholder(system, format)}).")
            }
            normalized += n
        }
        return Result.Valid(normalized.joinToString("+"))
    }
}

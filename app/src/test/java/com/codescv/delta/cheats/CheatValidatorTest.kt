package com.codescv.delta.cheats

import com.codescv.delta.model.GameSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatValidatorTest {
    private fun valid(code: String, system: GameSystem, format: CheatFormat): String {
        val r = CheatValidator.validate(code, system, format)
        assertTrue("$code should be valid for $system/$format: $r", r is CheatValidator.Result.Valid)
        return (r as CheatValidator.Result.Valid).normalized
    }

    private fun invalid(code: String, system: GameSystem, format: CheatFormat) {
        val r = CheatValidator.validate(code, system, format)
        assertTrue("$code should be invalid for $system/$format", r is CheatValidator.Result.Invalid)
    }

    @Test
    fun gbaGameSharkAndActionReplay() {
        assertEquals("82025D78 0063", valid("82025D78 0063", GameSystem.GBA, CheatFormat.CODE_BREAKER))
        assertEquals("1A2B3C4D 5E6F7A8B", valid("1a2b3c4d5e6f7a8b", GameSystem.GBA, CheatFormat.GAME_SHARK))
        assertEquals("1A2B3C4D 5E6F7A8B+00000000 11111111", valid("1a2b3c4d 5e6f7a8b\n00000000 11111111", GameSystem.GBA, CheatFormat.ACTION_REPLAY))
        invalid("1234", GameSystem.GBA, CheatFormat.GAME_SHARK)
        invalid("ZZZZZZZZ 00000000", GameSystem.GBA, CheatFormat.GAME_SHARK)
        invalid("82025D78 0063", GameSystem.GBA, CheatFormat.GAME_GENIE)
    }

    @Test
    fun nesGameGenie() {
        assertEquals("SXIOPO", valid("sxiopo", GameSystem.NES, CheatFormat.GAME_GENIE))
        assertEquals("AEUZZZZZ", valid("AEUZ-ZZZZ", GameSystem.NES, CheatFormat.GAME_GENIE))
        invalid("SXIOPB", GameSystem.NES, CheatFormat.GAME_GENIE) // B not in NES alphabet
        invalid("SXIOP", GameSystem.NES, CheatFormat.GAME_GENIE)
    }

    @Test
    fun snesFormats() {
        assertEquals("DD32-6DAD", valid("dd326dad", GameSystem.SNES, CheatFormat.GAME_GENIE))
        assertEquals("7E0DBE09", valid("7E0DBE09", GameSystem.SNES, CheatFormat.ACTION_REPLAY))
        invalid("XX32-6DAD", GameSystem.SNES, CheatFormat.GAME_GENIE)
    }

    @Test
    fun gameBoyFormats() {
        assertEquals("00A-17B-C49", valid("00A17BC49", GameSystem.GBC, CheatFormat.GAME_GENIE))
        assertEquals("010238CD", valid("010238cd", GameSystem.GB, CheatFormat.GAME_SHARK))
        invalid("01023", GameSystem.GBC, CheatFormat.GAME_SHARK)
    }

    @Test
    fun genesisN64AndNds() {
        assertEquals("RFAA-A6VR", valid("rfaaa6vr", GameSystem.GENESIS, CheatFormat.GAME_GENIE))
        assertEquals("FF0F1C:0063", valid("ff0f1c0063", GameSystem.GENESIS, CheatFormat.ACTION_REPLAY))
        assertEquals("8033B21E 0008", valid("8033B21E 0008", GameSystem.N64, CheatFormat.GAME_SHARK))
        assertEquals("221C2C4C 0000000F", valid("221c2c4c 0000000f", GameSystem.NDS, CheatFormat.ACTION_REPLAY))
        invalid("", GameSystem.N64, CheatFormat.GAME_SHARK)
    }

    @Test
    fun supportedFormatsPerSystem() {
        assertEquals(listOf(CheatFormat.GAME_GENIE), CheatFormat.supported(GameSystem.NES))
        assertEquals(listOf(CheatFormat.ACTION_REPLAY, CheatFormat.GAME_SHARK, CheatFormat.CODE_BREAKER), CheatFormat.supported(GameSystem.GBA))
        assertEquals(CheatFormat.supported(GameSystem.GB), CheatFormat.supported(GameSystem.GBC))
    }
}

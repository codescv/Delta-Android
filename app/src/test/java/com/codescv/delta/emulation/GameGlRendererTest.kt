package com.codescv.delta.emulation

import com.codescv.delta.settings.VideoFilter
import com.codescv.delta.skins.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameGlRendererTest {
    @Test
    fun screenDensityIsMinOutputPixelsPerCorePixel() {
        // GBA 240x160 drawn at 1080x720 (portrait full width) -> 4.5x.
        assertEquals(4.5f, GameGlRenderer.screenDensity(Box(0f, 0f, 1080f, 720f), Box(0f, 0f, 240f, 160f)), 1e-5f)
        // Letterboxed: the smaller axis ratio wins (LibretroDroid Video::getScreenDensity).
        assertEquals(4f, GameGlRenderer.screenDensity(Box(0f, 0f, 1200f, 640f), Box(0f, 0f, 240f, 160f)), 1e-5f)
        // NDS sub-rect: density is per source rect, not per whole texture.
        assertEquals(3f, GameGlRenderer.screenDensity(Box(0f, 0f, 768f, 576f), Box(0f, 192f, 256f, 192f)), 1e-5f)
    }

    @Test
    fun lcdShaderIsLibretroDroidLcd() {
        val v = GameGlRenderer.LCD_VERTEX
        val f = GameGlRenderer.LCD_FRAGMENT
        assertTrue(v.contains("screenCoords = coords * textureSize;"))
        assertTrue(v.contains("screenMaskStrength = smoothstep(2.0, 6.0, screenDensity);"))
        assertTrue(f.contains("#define INTENSITY 0.25"))
        assertTrue(f.contains("#define BRIGHTBOOST 0.25"))
        assertTrue(f.contains("lowp float mask = 1.0 - coords.x - coords.y;"))
        assertTrue(f.contains("gl_FragColor = vec4(mix(texel, mix(pixelLow, pixelHigh, mask), screenMaskStrength), 1.0);"))
        // No RGB subpixel stripes (the old shader's rainbow fringing).
        assertFalse(f.contains("0.78"))
        assertFalse(f.contains("gl_FragCoord"))
    }

    @Test
    fun shaderModesUnchangedForOtherFilters() {
        assertEquals(0, GameGlRenderer.shaderMode(VideoFilter.SHARP_PIXELS))
        assertEquals(2, GameGlRenderer.shaderMode(VideoFilter.CRT_SCANLINES))
        assertEquals(3, GameGlRenderer.shaderMode(VideoFilter.SMOOTH_BILINEAR))
    }
}

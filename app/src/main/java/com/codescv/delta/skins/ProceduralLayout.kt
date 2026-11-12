package com.codescv.delta.skins

/**
 * Layout of a built-in procedural skin variant (ported `ViewportLayout` + `ConsoleControlsLayout`),
 * produced by `ProceduralSkins.layout(...)` and drawn by `ProceduralSkinArtwork(...)`.
 * Game screens are rendered by the GL view underneath at [screens].
 */
interface ProceduralLayout : GamepadTouchMapper {
    /** Where the game screen(s) are drawn, in view pixels (NDS: top then bottom). */
    val screens: List<Box>
    /** Area covered by the controller artwork, in view pixels. */
    val controllerFrame: Box
    /** Whether the controls float translucently over the game (landscape). */
    val translucent: Boolean
}

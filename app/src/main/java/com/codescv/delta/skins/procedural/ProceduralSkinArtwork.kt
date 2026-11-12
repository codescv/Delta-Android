package com.codescv.delta.skins.procedural

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codescv.delta.model.GameSystem
import com.codescv.delta.model.VirtualButton
import com.codescv.delta.skins.ProceduralLayout

/** Procedural console shell + controls (implemented by the `skin-variants` helper). */
@Composable
fun ProceduralSkinArtwork(
    layout: ProceduralLayout,
    optionId: String,
    system: GameSystem,
    pressed: Set<VirtualButton>,
    held: Set<VirtualButton>,
    fastForwardActive: Boolean,
    fastForwardMultiplier: Int,
    modifier: Modifier = Modifier,
) {
}

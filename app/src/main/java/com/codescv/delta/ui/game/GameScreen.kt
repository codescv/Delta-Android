package com.codescv.delta.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.codescv.delta.library.RomEntry
import com.codescv.delta.ui.DeltaNavigator

/** Gameplay (design §5.2). Owned by `coder`. */
@Composable
fun GameScreen(rom: RomEntry, resumeStateId: String?, navigator: DeltaNavigator) {
    Box(Modifier.fillMaxSize().background(Color.Black))
}

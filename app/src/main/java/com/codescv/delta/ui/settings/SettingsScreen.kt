package com.codescv.delta.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.codescv.delta.ui.theme.DeltaColors

/** Settings sheet (design §5.5). Owned by the `settings-ui` helper. */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(DeltaColors.GroupedBackground)) { Text("Settings", color = Color.White) }
}

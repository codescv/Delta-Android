package com.codescv.delta.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.codescv.delta.ui.DeltaNavigator

/** Library root (design §5.1). Owned by the `library-ui` helper. */
@Composable
fun LibraryScreen(navigator: DeltaNavigator) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Delta", color = Color.White) }
}

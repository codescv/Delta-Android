package com.codescv.delta.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.codescv.delta.ui.game.GameScreen
import com.codescv.delta.ui.library.LibraryScreen
import com.codescv.delta.ui.settings.SettingsScreen
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaTheme

/** Root composable: Library (root) · Settings sheet · full-screen Game. */
@Composable
fun DeltaApp(navigator: DeltaNavigator = remember { DeltaNavigator() }) {
    DeltaTheme {
        Box(Modifier.fillMaxSize()) {
            when (val route = navigator.route) {
                DeltaRoute.Library -> {
                    Box(Modifier.fillMaxSize().background(DeltaColors.LibraryBackground)) {
                        LibraryScreen(navigator)
                    }
                }
                // No opaque Compose parent around the game: GLSurfaceView hole-punch rule (design §2.5).
                is DeltaRoute.Game -> GameScreen(route.rom, route.resumeStateId, navigator)
            }

            AnimatedVisibility(
                visible = navigator.settingsVisible && navigator.route is DeltaRoute.Library,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                BackHandler { navigator.closeSettings() }
                SettingsScreen(onClose = { navigator.closeSettings() })
            }

            navigator.launchError?.let { message ->
                AlertDialog(
                    onDismissRequest = { navigator.launchError = null },
                    title = { Text("Cannot Start Game") },
                    text = { Text(message) },
                    confirmButton = { TextButton(onClick = { navigator.launchError = null }) { Text("OK") } },
                )
            }
        }
    }
}

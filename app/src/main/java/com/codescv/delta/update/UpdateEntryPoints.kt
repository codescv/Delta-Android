package com.codescv.delta.update

import androidx.compose.runtime.Composable
import com.codescv.delta.ui.components.FormRow

/** Settings › Support › "Check for Updates" row (owned by the `updater` helper). */
@Composable
fun CheckForUpdatesRow() {
    FormRow(title = "Check for Updates", chevron = true)
}

/** Settings › Support › "反馈给 Agent" row + dialog (owned by the `updater` helper). */
@Composable
fun AgentFeedbackRow() {
    FormRow(title = "反馈给 Agent", chevron = true)
}

/** Silent update check on launch (24 h throttle); called once from MainActivity. */
@Composable
fun LaunchUpdateCheck() {
}

package com.codescv.delta.update

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.codescv.delta.R
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.theme.DeltaColors

/**
 * "检查更新 / Check for Updates" row (Settings top section). The
 * subtitle mirrors [UpdateManager.state] and the trailing value shows the installed version;
 * tapping opens [UpdateDialog] and starts a check unless an update is already pending.
 */
@Composable
fun CheckForUpdatesRow() {
    val context = LocalContext.current
    val manager = remember(context) { UpdateManager.get(context) }
    val state by manager.state.collectAsState()

    FormRow(
        icon = Icons.Outlined.SystemUpdate,
        iconColor = DeltaColors.BadgeBlue,
        title = stringResource(R.string.update_row_title),
        subtitle = updateStatusText(state, manager.currentVersion),
        value = "v${manager.currentVersion}",
        chevron = true,
        onClick = {
            manager.showDialog()
            when (state) {
                is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Downloaded -> Unit
                else -> manager.checkForUpdates(silent = false)
            }
        },
    )
    UpdateDialogHost(manager)
}

@Composable
private fun updateStatusText(
    state: UpdateState,
    currentVersion: String,
): String =
    when (state) {
        UpdateState.Idle -> stringResource(R.string.update_status_idle, currentVersion)
        UpdateState.Checking -> stringResource(R.string.update_status_checking)
        is UpdateState.UpToDate -> stringResource(R.string.update_status_up_to_date, state.latest.version)
        is UpdateState.Available -> stringResource(R.string.update_status_available, state.latest.version)
        is UpdateState.Downloading -> stringResource(R.string.update_status_downloading, state.latest.version)
        is UpdateState.Downloaded -> stringResource(R.string.update_status_downloaded, state.latest.version)
        is UpdateState.Error ->
            stringResource(R.string.update_status_error, stringResource(UpdateManager.errorStringRes(state.code)))
    }

/** "反馈给 Agent / Send Feedback" row + [AgentFeedbackDialog] (Settings). */
@Composable
fun AgentFeedbackRow() {
    val context = LocalContext.current
    var savedEmail by remember(context) { mutableStateOf(AgentFeedbackHelper.getSavedEmail(context)) }
    var showDialog by rememberSaveable { mutableStateOf(false) }
    // Re-read the stored address whenever the dialog opens or closes (it may change elsewhere).
    LaunchedEffect(showDialog) { savedEmail = AgentFeedbackHelper.getSavedEmail(context) }

    FormRow(
        icon = Icons.Outlined.Feedback,
        iconColor = DeltaColors.BadgePurple,
        title = stringResource(R.string.agent_feedback_title),
        subtitle =
            if (savedEmail.isBlank()) {
                stringResource(R.string.agent_feedback_subtitle_unset)
            } else {
                stringResource(R.string.agent_feedback_subtitle_configured, savedEmail)
            },
        chevron = true,
        onClick = { showDialog = true },
    )

    if (showDialog) {
        AgentFeedbackDialog(
            initialEmail = savedEmail,
            onEmailSaved = { savedEmail = it },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Silent update check on every cold start (once per process, no 24 h lockout, like Lemuroid);
 * called once from MainActivity. When a newer release is found the update dialog
 * is shown from here.
 */
@Composable
fun LaunchUpdateCheck() {
    val context = LocalContext.current
    val manager = remember(context) { UpdateManager.get(context) }
    LaunchedEffect(manager) { manager.autoCheckIfDue() }
    UpdateDialogHost(manager)
}

package com.codescv.delta.update

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codescv.delta.BuildConfig
import com.codescv.delta.R

@Composable
fun AgentFeedbackDialog(
    initialEmail: String,
    currentVersion: String = BuildConfig.VERSION_NAME,
    onEmailSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    var emailInput by rememberSaveable { mutableStateOf(initialEmail) }
    // Baseline used to decide whether "save email" is offered. It follows initialEmail (which the caller updates
    // through onEmailSaved) and survives configuration changes while the dialog stays open.
    var lastSavedEmail by rememberSaveable(initialEmail) {
        mutableStateOf(AgentFeedbackHelper.normalizeEmail(initialEmail))
    }
    var summaryInput by rememberSaveable { mutableStateOf("") }
    var detailsInput by rememberSaveable { mutableStateOf("") }
    var emailError by rememberSaveable { mutableStateOf(false) }
    var summaryError by rememberSaveable { mutableStateOf(false) }
    var attachedImageUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var attachedImageName by rememberSaveable { mutableStateOf<String?>(null) }

    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                attachedImageUri = uri
                attachedImageName =
                    AgentFeedbackHelper.sanitizeAttachmentName(AgentFeedbackHelper.queryDisplayName(context, uri))
                if (summaryError) summaryError = false
            }
        }

    val normalizedEmail = AgentFeedbackHelper.normalizeEmail(emailInput)
    val effectiveSummary =
        AgentFeedbackHelper.resolveSummary(
            summary = summaryInput,
            details = detailsInput,
            attachmentName = if (attachedImageUri != null) attachedImageName else null,
        )
    val subjectPreview = AgentFeedbackHelper.formatSubject(effectiveSummary, currentVersion)
    val emailChanged = AgentFeedbackHelper.isEmailChanged(emailInput, lastSavedEmail)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(id = R.string.agent_feedback_dialog_title))
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.agent_feedback_subject_preview, subjectPreview),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        if (emailError) emailError = false
                    },
                    label = { Text(text = stringResource(id = R.string.agent_feedback_email_label)) },
                    placeholder = { Text(text = AgentFeedbackHelper.EMAIL_PLACEHOLDER) },
                    isError = emailError,
                    supportingText = {
                        if (emailError) {
                            Text(text = stringResource(id = R.string.agent_feedback_email_invalid))
                        } else {
                            Text(text = stringResource(id = R.string.agent_feedback_email_hint))
                        }
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = summaryInput,
                    onValueChange = {
                        summaryInput = it
                        if (summaryError) summaryError = false
                    },
                    label = { Text(text = stringResource(id = R.string.agent_feedback_summary_label)) },
                    placeholder = { Text(text = stringResource(id = R.string.agent_feedback_summary_placeholder)) },
                    isError = summaryError,
                    supportingText =
                        if (summaryError) {
                            { Text(text = stringResource(id = R.string.agent_feedback_summary_required)) }
                        } else {
                            null
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = detailsInput,
                    onValueChange = {
                        detailsInput = it
                        if (summaryError) summaryError = false
                    },
                    label = { Text(text = stringResource(id = R.string.agent_feedback_details_label)) },
                    placeholder = { Text(text = stringResource(id = R.string.agent_feedback_details_placeholder)) },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            stringResource(
                                id =
                                    if (attachedImageUri == null) {
                                        R.string.agent_feedback_add_image
                                    } else {
                                        R.string.agent_feedback_change_image
                                    },
                            ),
                    )
                }

                if (attachedImageUri != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = attachedImageName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                attachedImageUri = null
                                attachedImageName = null
                            },
                        ) {
                            Text(text = "✕ " + stringResource(id = R.string.agent_feedback_remove_image))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val validEmail = AgentFeedbackHelper.isValidEmail(normalizedEmail)
                    val hasContent =
                        summaryInput.isNotBlank() || detailsInput.isNotBlank() || attachedImageUri != null
                    emailError = !validEmail
                    summaryError = !hasContent
                    if (!validEmail || !hasContent) return@Button

                    if (AgentFeedbackHelper.saveEmail(context, normalizedEmail)) {
                        lastSavedEmail = normalizedEmail
                        onEmailSaved(normalizedEmail)
                    }
                    val launched =
                        AgentFeedbackHelper.launchFeedbackEmail(
                            context = context,
                            email = normalizedEmail,
                            summary = effectiveSummary,
                            details = detailsInput,
                            versionName = currentVersion,
                            attachmentUri = attachedImageUri,
                            attachmentName = attachedImageName,
                        )
                    if (launched) {
                        onDismiss()
                    }
                },
            ) {
                Text(text = stringResource(id = R.string.agent_feedback_action_send))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(id = R.string.agent_feedback_action_cancel))
                }
                if (emailChanged) {
                    TextButton(
                        onClick = {
                            // Saving the email keeps the dialog open: title, description and image are preserved.
                            val result =
                                AgentFeedbackHelper.saveEmailFromDialog(
                                    emailInput = emailInput,
                                    lastSavedEmail = lastSavedEmail,
                                    persist = { AgentFeedbackHelper.saveEmail(context, it) },
                                )
                            emailInput = result.emailInput
                            emailError = result.emailError
                            lastSavedEmail = result.lastSavedEmail
                            if (result.saved) {
                                onEmailSaved(result.lastSavedEmail)
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.agent_feedback_email_saved),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            if (result.shouldDismiss) {
                                onDismiss()
                            }
                        },
                    ) {
                        Text(text = stringResource(id = R.string.agent_feedback_action_save_email))
                    }
                }
            }
        },
    )
}

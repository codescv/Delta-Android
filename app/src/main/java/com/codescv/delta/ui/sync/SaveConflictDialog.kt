package com.codescv.delta.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.codescv.delta.sync.ConflictChoice
import com.codescv.delta.sync.SyncConflict
import com.codescv.delta.sync.SyncConflictKind
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Interactive two-choice save conflict resolver shown when [ConflictPolicy.ASK] detects
 * diverged local and Google Drive saves.
 */
@Composable
fun SaveConflictDialog(
    conflicts: List<SyncConflict>,
    resolvingConflictId: String?,
    onResolve: suspend (SyncConflict, ConflictChoice) -> Result<Unit>,
    onDismiss: () -> Unit,
) {
    val conflict = conflicts.firstOrNull() ?: return
    val scope = rememberCoroutineScope()

    // Track batch progress so counter advances naturally (e.g. 1 / 2 -> 2 / 2).
    var resolvedInBatch by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(conflicts.size) }
    LaunchedEffect(conflicts.size) {
        val impliedTotal = resolvedInBatch + conflicts.size
        if (impliedTotal > batchTotal) {
            batchTotal = impliedTotal
        } else if (conflicts.size == batchTotal && resolvedInBatch > 0) {
            resolvedInBatch = 0
        }
    }
    val displayTotal = maxOf(batchTotal, conflicts.size)
    val displayIndex = (displayTotal - conflicts.size + 1).coerceIn(1, displayTotal)

    var activeChoice by remember(conflict.id) { mutableStateOf<ConflictChoice?>(null) }
    var actionError by remember(conflict.id) { mutableStateOf<String?>(null) }
    val isResolving = resolvingConflictId != null || activeChoice != null

    val localIsNewer = conflict.localMtimeMs >= conflict.remoteMtimeMs
    val remoteIsNewer = conflict.remoteMtimeMs > conflict.localMtimeMs
    val fileTypeLabel = when (conflict.kind) {
        SyncConflictKind.BATTERY_SAVE -> "Battery Save (.srm)"
        SyncConflictKind.SAVE_STATE -> {
            val label = conflict.stateLabel?.takeIf { it.isNotBlank() }
                ?: conflict.stateId?.takeIf { it.isNotBlank() }
                ?: "State"
            "Save State · $label"
        }
    }

    Dialog(
        onDismissRequest = { if (!isResolving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isResolving,
            dismissOnClickOutside = !isResolving,
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(DeltaColors.GroupedBackground)
                .border(1.dp, DeltaColors.GlassStroke, RoundedCornerShape(22.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            // Top bar: warning badge + title + counter pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(DeltaColors.BadgeOrange.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = DeltaColors.BadgeOrange,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Save Conflict",
                    style = DeltaType.Title3,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(DeltaColors.GroupedCard)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "$displayIndex / $displayTotal",
                        style = DeltaType.Caption,
                        fontWeight = FontWeight.SemiBold,
                        color = DeltaColors.SecondaryText,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Game title + file type
            Text(
                text = conflict.gameName,
                style = DeltaType.Headline,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = fileTypeLabel,
                style = DeltaType.Footnote,
                fontWeight = FontWeight.Medium,
                color = DeltaColors.BadgePurple,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Both this device and Google Drive have different versions. Choose which one to keep.",
                style = DeltaType.Footnote,
                color = DeltaColors.SecondaryText,
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(16.dp))

            // Card 1: This Device
            ConflictSideCard(
                title = "This Device",
                icon = Icons.Outlined.PhoneAndroid,
                accentColor = DeltaColors.Purple,
                timestampMs = conflict.localMtimeMs,
                sizeBytes = conflict.localSizeBytes,
                deviceDetail = null,
                isNewer = localIsNewer,
            )

            Spacer(Modifier.height(10.dp))

            // Card 2: Google Drive
            ConflictSideCard(
                title = "Google Drive",
                icon = Icons.Outlined.Cloud,
                accentColor = DeltaColors.BadgeBlue,
                timestampMs = conflict.remoteMtimeMs,
                sizeBytes = conflict.remoteSizeBytes,
                deviceDetail = conflict.remoteDeviceName?.takeIf { it.isNotBlank() }?.let { "From $it" },
                isNewer = remoteIsNewer,
            )

            if (actionError != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DeltaColors.BadgeRed.copy(alpha = 0.14f))
                        .border(0.5.dp, DeltaColors.BadgeRed.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = actionError.orEmpty(),
                        style = DeltaType.Caption,
                        color = DeltaColors.BadgeRed,
                        lineHeight = 16.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Action 1: Keep This Device
            Button(
                onClick = {
                    if (isResolving) return@Button
                    activeChoice = ConflictChoice.KEEP_LOCAL
                    actionError = null
                    scope.launch {
                        val res = onResolve(conflict, ConflictChoice.KEEP_LOCAL)
                        activeChoice = null
                        res.onSuccess {
                            resolvedInBatch++
                        }.onFailure { err ->
                            actionError = err.message ?: "Could not upload local save."
                        }
                    }
                },
                enabled = !isResolving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeltaColors.Purple,
                    contentColor = Color.White,
                    disabledContainerColor = DeltaColors.Purple.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (activeChoice == ConflictChoice.KEEP_LOCAL) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Uploading…", style = DeltaType.Subheadline, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Keep This Device", style = DeltaType.Subheadline, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Action 2: Use Google Drive
            Button(
                onClick = {
                    if (isResolving) return@Button
                    activeChoice = ConflictChoice.USE_CLOUD
                    actionError = null
                    scope.launch {
                        val res = onResolve(conflict, ConflictChoice.USE_CLOUD)
                        activeChoice = null
                        res.onSuccess {
                            resolvedInBatch++
                        }.onFailure { err ->
                            actionError = err.message ?: "Could not apply Google Drive save."
                        }
                    }
                },
                enabled = !isResolving,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeltaColors.BadgeBlue,
                    contentColor = Color.White,
                    disabledContainerColor = DeltaColors.BadgeBlue.copy(alpha = 0.45f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (activeChoice == ConflictChoice.USE_CLOUD) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Downloading…", style = DeltaType.Subheadline, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("Use Google Drive", style = DeltaType.Subheadline, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = "A local .bak backup is saved before replacing with Google Drive.",
                style = DeltaType.Caption2,
                color = DeltaColors.SystemGray,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.height(6.dp))

            // Dismiss: Later
            TextButton(
                onClick = onDismiss,
                enabled = !isResolving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = DeltaColors.SecondaryText,
                    disabledContentColor = DeltaColors.SystemGray.copy(alpha = 0.5f),
                ),
            ) {
                Text("Later", style = DeltaType.Subheadline, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ConflictSideCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    timestampMs: Long,
    sizeBytes: Long,
    deviceDetail: String?,
    isNewer: Boolean,
) {
    val formattedDate = remember(timestampMs) { formatConflictDate(timestampMs) }
    val formattedSize = remember(sizeBytes) { formatConflictBytes(sizeBytes) }
    val borderColor = if (isNewer) accentColor.copy(alpha = 0.55f) else DeltaColors.GlassStroke

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DeltaColors.GroupedCard)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = DeltaType.Subheadline,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            if (isNewer) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(DeltaColors.BadgeGreen.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Newer",
                        style = DeltaType.Caption2,
                        fontWeight = FontWeight.SemiBold,
                        color = DeltaColors.BadgeGreen,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formattedDate,
                style = DeltaType.Footnote,
                color = DeltaColors.CardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = formattedSize,
                style = DeltaType.Footnote,
                fontWeight = FontWeight.Medium,
                color = DeltaColors.SecondaryText,
            )
        }
        if (deviceDetail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = deviceDetail,
                style = DeltaType.Caption,
                color = DeltaColors.SystemGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun formatConflictDate(epochMs: Long): String {
    if (epochMs <= 0L) return "Unknown date"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
}

internal fun formatConflictBytes(bytes: Long): String = when {
    bytes < 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
}

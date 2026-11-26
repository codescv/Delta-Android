package com.codescv.delta.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.codescv.delta.DeltaApplication
import com.codescv.delta.i18n.DeltaI18n
import com.codescv.delta.i18n.LocalDeltaStrings
import com.codescv.delta.i18n.StrKey
import com.codescv.delta.i18n.str
import com.codescv.delta.model.GameSystem
import com.codescv.delta.settings.AppPreferences
import com.codescv.delta.sync.CancellationIOException
import com.codescv.delta.sync.ConflictPolicy
import com.codescv.delta.sync.GoogleOAuthFlow
import com.codescv.delta.ui.components.FormDivider
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.components.FormSection
import com.codescv.delta.ui.components.FormToggleRow
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ---------------------------------------------------------------------------------- shared

@Composable
internal fun DeltaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = DeltaColors.Purple,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Color(0xFF39393D),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** `LabeledContent` whose value may be long (wraps, right-aligned). */
@Composable
private fun LabeledRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = DeltaType.Body, color = Color.White, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = DeltaType.Body,
            color = if (onClick != null) DeltaColors.Purple else DeltaColors.SystemGray,
            textAlign = TextAlign.End,
            maxLines = 2,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

// --------------------------------------------------------------------------------- App Icon

@Composable
internal fun AppIconSettings(prefs: AppPreferences, nav: SettingsNav) {
    val context = LocalContext.current
    val strings = LocalDeltaStrings.current
    val scope = rememberCoroutineScope()
    val selected = AppIcons.find(prefs.selectedAppIconName).imageName
    SettingsPage {
        AppIcons.bySection().forEach { (section, icons) ->
            FormSection(header = section.localizedTitle(strings)) {
                icons.forEachIndexed { i, icon ->
                    if (i > 0) {
                        Box(Modifier.fillMaxWidth().padding(start = 72.dp).height(0.5.dp).background(DeltaColors.Separator))
                    }
                    AppIconRow(icon, icon.imageName == selected) {
                        if (icon.imageName == selected) return@AppIconRow
                        nav.update { it.copy(selectedAppIconName = icon.imageName) }
                        scope.launch {
                            withContext(Dispatchers.IO) { AppIcons.apply(context.applicationContext, icon.imageName) }
                            nav.message(AppIcons.updateMessage(strings))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIconRow(icon: DeltaAppIcon, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF000000 or icon.accentHex)),
            contentAlignment = Alignment.Center,
        ) {
            Text("Δ", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(icon.name, style = DeltaType.Body, fontWeight = FontWeight.Medium, color = Color.White)
            icon.credit?.let { Text(it, style = DeltaType.Caption, color = DeltaColors.SystemGray) }
        }
        if (selected) {
            Icon(Icons.Filled.CheckCircle, contentDescription = str(StrKey.COMMON_SELECTED), tint = DeltaColors.Purple, modifier = Modifier.size(24.dp))
        }
    }
}

// ------------------------------------------------------------------------------- Services

@Composable
internal fun DeltaSyncSettings(prefs: AppPreferences, nav: SettingsNav) {
    val context = LocalContext.current
    val strings = LocalDeltaStrings.current
    val scope = rememberCoroutineScope()
    val container = DeltaApplication.container
    val sync = container.sync
    val syncState by sync.uiState.collectAsState()

    var clientIdInput by remember(syncState.clientId) { mutableStateOf(syncState.clientId) }

    // Debounce persisting OAuth Client ID off the main thread (avoids Keystore crypto per keystroke).
    val latestClientId by rememberUpdatedState(clientIdInput)
    LaunchedEffect(clientIdInput) {
        delay(400)
        withContext(Dispatchers.IO) {
            sync.saveOAuthConfig(clientIdInput)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            val id = latestClientId
            container.ioScope.launch { sync.saveOAuthConfig(id) }
        }
    }

    SettingsPage {
        // Pending save conflicts banner (always visible at top when conflicts await review)
        if (syncState.pendingConflicts.isNotEmpty()) {
            val conflictCount = syncState.pendingConflicts.size
            val conflictTitle = if (conflictCount == 1) {
                strings[StrKey.SYNC_CONFLICT_ONE]
            } else {
                strings.format(StrKey.SYNC_CONFLICT_MANY, conflictCount)
            }
            FormSection {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(DeltaColors.BadgeOrange.copy(alpha = 0.16f))
                        .clickable { sync.showConflictPrompt() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(DeltaColors.BadgeOrange.copy(alpha = 0.22f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = DeltaColors.BadgeOrange,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = conflictTitle,
                            style = DeltaType.Body,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            text = str(StrKey.SYNC_TAP_TO_RESOLVE),
                            style = DeltaType.Caption,
                            color = DeltaColors.BadgeOrange,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = DeltaColors.BadgeOrange,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 1. Google Drive connection status + Sign In / Sign Out
        FormSection(header = "Google Drive") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (syncState.isSignedIn) DeltaColors.BadgeGreen else DeltaColors.SystemGray),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (syncState.isSignedIn) {
                            syncState.accountName.ifBlank { strings[StrKey.SYNC_CONNECTED] }
                        } else {
                            strings[StrKey.SYNC_NOT_CONNECTED]
                        },
                        style = DeltaType.Body,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (syncState.isSignedIn) {
                            syncState.accountEmail.ifBlank { "Google Drive" }
                        } else {
                            strings[StrKey.SYNC_SIGN_IN_SUBTITLE]
                        },
                        style = DeltaType.Caption,
                        color = DeltaColors.SystemGray,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FormDivider()
            when {
                syncState.isSigningIn -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = DeltaColors.Purple,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            str(StrKey.SYNC_COMPLETE_IN_BROWSER),
                            style = DeltaType.Body,
                            color = DeltaColors.SystemGray,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            str(StrKey.COMMON_CANCEL),
                            style = DeltaType.Body,
                            color = DestructiveRed,
                            modifier = Modifier
                                .clickable { sync.cancelSignIn() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                syncState.isSignedIn -> {
                    ActionRow(
                        title = str(StrKey.SYNC_SIGN_OUT),
                        icon = Icons.AutoMirrored.Outlined.Logout,
                        color = DestructiveRed,
                        enabled = !syncState.isSyncing && syncState.resolvingConflictId == null,
                    ) {
                        scope.launch {
                            sync.signOut()
                            nav.message(strings[StrKey.SYNC_SIGNED_OUT_MSG])
                        }
                    }
                }
                else -> {
                    ActionRow(
                        title = str(StrKey.SYNC_SIGN_IN_GOOGLE),
                        icon = Icons.Outlined.Cloud,
                        color = DeltaColors.Purple,
                    ) {
                        val idToSave = clientIdInput.trim()
                        if (idToSave.isBlank()) {
                            nav.message(strings[StrKey.SYNC_ENTER_CLIENT_ID_FIRST])
                            return@ActionRow
                        }
                        if (GoogleOAuthFlow.deriveRedirectUriOrNull(idToSave) == null) {
                            nav.message(strings[StrKey.SYNC_CLIENT_ID_SUFFIX_ERR])
                            return@ActionRow
                        }
                        // Run in process-wide ioScope so Activity recreation while the browser is open
                        // neither drops the OAuth redirect nor loses the tokens.
                        container.ioScope.launch {
                            sync.saveOAuthConfig(idToSave)
                            runCatching {
                                sync.signIn { authUrl ->
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    try {
                                        context.applicationContext.startActivity(intent)
                                    } catch (e: ActivityNotFoundException) {
                                        throw IOException(DeltaI18n.tr(StrKey.SYNC_NO_BROWSER_ERR), e)
                                    }
                                }
                            }.onSuccess { info ->
                                val label = info.email.ifBlank { info.displayName.ifBlank { "Google Drive" } }
                                withContext(Dispatchers.Main) { nav.message(DeltaI18n.tr(StrKey.SYNC_CONNECTED_TO, label)) }
                            }.onFailure { err ->
                                if (err !is CancellationIOException) {
                                    withContext(Dispatchers.Main) {
                                        nav.message(err.message ?: DeltaI18n.tr(StrKey.SYNC_SIGN_IN_FAILED))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!syncState.isSignedIn && syncState.lastError != null) {
                FormDivider()
                Text(
                    text = syncState.lastError.orEmpty(),
                    style = DeltaType.Caption,
                    color = DestructiveRed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        // 2. GCP OAuth Setup
        val redirectPreview = GoogleOAuthFlow.deriveRedirectUriOrNull(clientIdInput)
            ?: "com.googleusercontent.apps.<client-id-prefix>:/oauth2redirect"
        FormSection(
            header = str(StrKey.SYNC_GCP_OAUTH_HEADER),
            footer = str(StrKey.SYNC_GCP_OAUTH_FOOTER, redirectPreview),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(str(StrKey.SYNC_OAUTH_CLIENT_ID_LABEL), style = DeltaType.Caption, color = DeltaColors.SystemGray)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (clientIdInput.isEmpty()) {
                        Text(
                            "1234567890-abc.apps.googleusercontent.com",
                            style = DeltaType.Body,
                            color = Color(0xFF5A5A5F),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = clientIdInput,
                        onValueChange = { v -> if (!syncState.isSignedIn) clientIdInput = v.trim() },
                        readOnly = syncState.isSignedIn,
                        singleLine = true,
                        textStyle = DeltaType.Body.copy(
                            color = if (syncState.isSignedIn) DeltaColors.SystemGray else Color.White,
                        ),
                        cursorBrush = SolidColor(DeltaColors.Purple),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (syncState.isSignedIn) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        str(StrKey.SYNC_SIGN_OUT_TO_CHANGE_ID),
                        style = DeltaType.Caption,
                        color = DeltaColors.SystemGray,
                    )
                }
            }
        }

        // 3. Sync Controls (visible when connected)
        if (syncState.isSignedIn) {
            FormSection(
                header = str(StrKey.SYNC_CONTROLS_HEADER),
                footer = str(StrKey.SYNC_CONTROLS_FOOTER),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clickable(enabled = !syncState.isSyncing && syncState.resolvingConflictId == null) {
                            scope.launch {
                                val res = sync.syncNow()
                                nav.message(res.summaryText(DeltaI18n.currentStrings()))
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (syncState.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = DeltaColors.Purple,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Sync,
                            contentDescription = null,
                            tint = DeltaColors.Purple,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = if (syncState.isSyncing) {
                            syncState.progressMessage ?: str(StrKey.SYNC_SYNCING)
                        } else {
                            str(StrKey.SYNC_NOW)
                        },
                        style = DeltaType.Body,
                        color = if (syncState.isSyncing) DeltaColors.SystemGray else DeltaColors.Purple,
                    )
                }

                val lastResult = syncState.lastResult
                val lastSyncedAt = syncState.lastSyncedAtMs
                val lastError = syncState.lastError
                if (lastResult != null || lastSyncedAt > 0L || lastError != null) {
                    FormDivider()
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        if (lastResult != null) {
                            val summary = buildString {
                                append(lastResult.summaryText(strings))
                                if (lastResult.conflicts > 0 && lastResult.pendingConflicts.isEmpty()) {
                                    append(" · ")
                                    append(strings.format(StrKey.SYNC_CONFLICT_COUNT_INLINE, lastResult.conflicts))
                                }
                                if (lastResult.skippedRunningGames > 0) {
                                    append(" · ")
                                    append(strings.format(StrKey.SYNC_RUNNING_SKIPPED, lastResult.skippedRunningGames))
                                }
                            }
                            Text(summary, style = DeltaType.Caption, color = Color.White)
                        }
                        if (lastSyncedAt > 0L) {
                            val formatted = remember(lastSyncedAt, strings.language) {
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, strings.language.locale)
                                    .format(Date(lastSyncedAt))
                            }
                            Text(str(StrKey.SYNC_LAST_SYNCED, formatted), style = DeltaType.Caption, color = DeltaColors.SystemGray)
                        }
                        if (lastError != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(lastError, style = DeltaType.Caption, color = DestructiveRed)
                        }
                    }
                }

                FormDivider()
                FormToggleRow(
                    title = str(StrKey.SYNC_AUTO_SYNC),
                    checked = prefs.syncAutoEnabled,
                    onCheckedChange = { v -> nav.update { it.copy(syncAutoEnabled = v) } },
                )
                FormDivider()
                FormToggleRow(
                    title = str(StrKey.SYNC_BATTERY_SAVES),
                    checked = prefs.syncBatterySaves,
                    onCheckedChange = { v -> nav.update { it.copy(syncBatterySaves = v) } },
                )
                FormDivider()
                FormToggleRow(
                    title = str(StrKey.SYNC_SAVE_STATES),
                    checked = prefs.syncSaveStates,
                    onCheckedChange = { v -> nav.update { it.copy(syncSaveStates = v) } },
                )
                FormDivider()
                PickerRow(
                    title = str(StrKey.SYNC_CONFLICT_RESOLUTION),
                    options = ConflictPolicy.entries.map { it to it.localizedTitle(strings) },
                    selected = ConflictPolicy.fromRaw(prefs.syncConflictPolicy),
                    onSelect = { policy -> nav.update { it.copy(syncConflictPolicy = policy.raw) } },
                )
            }
        }
    }
}

@Composable
internal fun RetroAchievementsSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(header = str(StrKey.RA_ACCOUNT_HEADER)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                if (prefs.retroAchievementsUsername.isEmpty()) {
                    Text(str(StrKey.RA_USERNAME_PLACEHOLDER), style = DeltaType.Body, color = Color(0xFF5A5A5F))
                }
                BasicTextField(
                    value = prefs.retroAchievementsUsername,
                    onValueChange = { v -> nav.update { it.copy(retroAchievementsUsername = v.trim()) } },
                    singleLine = true,
                    textStyle = DeltaType.Body.copy(color = Color.White),
                    cursorBrush = SolidColor(DeltaColors.Purple),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FormSection(footer = str(StrKey.RA_HARDCORE_FOOTER)) {
            FormToggleRow(str(StrKey.RA_HARDCORE_MODE), prefs.retroAchievementsHardcore, { v -> nav.update { it.copy(retroAchievementsHardcore = v) } })
        }
    }
}

// ---------------------------------------------------------------------------- Miscellaneous

@Composable
internal fun LilSettings(prefs: AppPreferences, nav: SettingsNav) {
    SettingsPage {
        FormSection(footer = str(StrKey.LIL_RESUME_FOOTER)) {
            FormToggleRow(str(StrKey.LIL_RESUME_TITLE), prefs.autoResume, { v -> nav.update { it.copy(autoResume = v) } })
        }
    }
}

@Composable
internal fun AdvancedSettings(nav: SettingsNav) {
    val context = LocalContext.current
    val library = DeltaApplication.container.library
    val state by library.state.collectAsState()
    SettingsPage {
        FormSection(header = str(StrKey.ADV_STORAGE_HEADER)) {
            FormRow(title = str(StrKey.SETTINGS_GAME_LIBRARY), chevron = true, onClick = { nav.push(SettingsRoute.GameLibrary) })
            FormDivider()
            FormRow(title = str(StrKey.ADV_ROMS_DIRECTORY), subtitle = library.romsDirectory.absolutePath)
            FormDivider()
            FormRow(title = str(StrKey.ADV_TOTAL_GAMES), value = state.entries.size.toString())
        }
        FormSection(header = str(StrKey.ADV_ABOUT_HEADER)) {
            FormRow(title = str(StrKey.ADV_DELTA_VERSION), value = SettingsData.VERSION_STRING)
            FormDivider()
            LabeledRow(str(StrKey.ADV_GITHUB_FORK), SettingsData.GITHUB_FORK) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://" + SettingsData.GITHUB_FORK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------- Game Library

@Composable
internal fun GameLibrarySettings(prefs: AppPreferences, nav: SettingsNav) {
    val context = LocalContext.current
    val strings = LocalDeltaStrings.current
    val scope = rememberCoroutineScope()
    val library = DeltaApplication.container.library
    val state by library.state.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }

    fun run(label: String, work: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch {
            val msg = runCatching { withContext(Dispatchers.IO) { work() } }.getOrElse { it.message ?: it.toString() }
            busy = null
            nav.message(msg)
        }
    }

    val filesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run(strings[StrKey.GL_IMPORTING]) { importUris(context, uris) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) run(strings[StrKey.GL_IMPORTING]) { importFolder(context, uri) }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) run(strings[StrKey.GL_EXPORTING]) { exportSaves(context, uri) }
    }

    SettingsPage {
        FormSection(header = str(StrKey.GL_BOX_ART_HEADER), footer = str(StrKey.GL_BOX_ART_FOOTER)) {
            FormToggleRow(str(StrKey.GL_AUTO_FETCH_BOX_ART), prefs.autoFetchBoxArt, { v -> nav.update { it.copy(autoFetchBoxArt = v) } })
            FormDivider()
            ActionRow(str(StrKey.GL_RESCAN_ARTWORK), Icons.Outlined.Image, enabled = busy == null) {
                run(strings[StrKey.GL_FETCHING_ARTWORK]) { rescanArtwork() }
            }
        }
        FormSection(header = str(StrKey.GL_IMPORT_HEADER)) {
            ActionRow(str(StrKey.GL_IMPORT_FILES), Icons.Outlined.NoteAdd, enabled = busy == null) { filesPicker.launch(arrayOf("*/*")) }
            FormDivider()
            ActionRow(str(StrKey.GL_IMPORT_FOLDER), Icons.Outlined.CreateNewFolder, enabled = busy == null) { folderPicker.launch(null) }
        }
        FormSection(header = str(StrKey.GL_EXPORT_HEADER), footer = str(StrKey.GL_EXPORT_FOOTER)) {
            ActionRow(str(StrKey.GL_EXPORT_ALL_SAVES), Icons.Outlined.Archive, enabled = busy == null) { exporter.launch("Delta Saves.zip") }
        }
        busy?.let { FormSection { CaptionRow(it) } }
        FormSection(header = str(StrKey.ADV_STORAGE_HEADER)) {
            FormRow(title = str(StrKey.ADV_ROMS_DIRECTORY), subtitle = library.romsDirectory.absolutePath)
            FormDivider()
            FormRow(title = str(StrKey.ADV_TOTAL_GAMES), value = state.entries.size.toString())
        }
    }
}

private val importableExtensions: Set<String> by lazy {
    (GameSystem.entries.flatMap { it.fileExtensions } + "zip").toSet()
}

private fun nameOf(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "game"

private fun importOne(context: Context, uri: Uri, name: String): Int {
    val library = DeltaApplication.container.library
    return context.contentResolver.openInputStream(uri)?.use { library.import(it, name).size } ?: 0
}

private fun importSummary(imported: Int, failures: List<String>): String = buildString {
    append(if (imported == 1) DeltaI18n.tr(StrKey.GL_IMPORTED_ONE) else DeltaI18n.tr(StrKey.GL_IMPORTED_MANY, imported))
    if (failures.isNotEmpty()) {
        append(" ")
        append(DeltaI18n.tr(StrKey.GL_COULD_NOT_IMPORT, failures.take(3).joinToString()))
    }
    if (failures.size > 3) append(" …")
}

private fun importUris(context: Context, uris: List<Uri>): String {
    var count = 0
    val failures = mutableListOf<String>()
    for (uri in uris) {
        val name = nameOf(context, uri)
        runCatching { count += importOne(context, uri, name) }.onFailure { failures += name }
    }
    return importSummary(count, failures)
}

private fun importFolder(context: Context, tree: Uri): String {
    val root = DocumentFile.fromTreeUri(context, tree) ?: return DeltaI18n.tr(StrKey.GL_UNABLE_OPEN_FOLDER)
    var count = 0
    val failures = mutableListOf<String>()
    fun walk(dir: DocumentFile, depth: Int) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                if (depth < 4) walk(f, depth + 1)
                continue
            }
            val name = f.name ?: continue
            if (name.substringAfterLast('.', "").lowercase() !in importableExtensions) continue
            runCatching { count += importOne(context, f.uri, name) }.onFailure { failures += name }
        }
    }
    walk(root, 0)
    return importSummary(count, failures)
}

private fun rescanArtwork(): String {
    val library = DeltaApplication.container.library
    val missing = library.value.entries.filter { library.covers.coverIfExists(it) == null }
    var fetched = 0
    for (rom in missing) {
        if (runCatching { library.covers.fetchCoverIfNeeded(rom, force = true) }.getOrNull() != null) fetched++
    }
    library.bumpCovers()
    return if (missing.isEmpty()) {
        DeltaI18n.tr(StrKey.GL_ALL_ARTWORK_PRESENT)
    } else {
        DeltaI18n.tr(StrKey.GL_FETCHED_ARTWORK_COUNT, fetched, missing.size)
    }
}

private fun exportSaves(context: Context, target: Uri): String {
    val root = DeltaApplication.container.root
    var files = 0
    context.contentResolver.openOutputStream(target)?.use { out ->
        ZipOutputStream(out.buffered()).use { zip ->
            for (dirName in listOf("Saves", "States")) {
                val dir = File(root, dirName)
                if (!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    files++
                }
            }
        }
    } ?: return DeltaI18n.tr(StrKey.GL_UNABLE_CREATE_EXPORT)
    return if (files == 1) DeltaI18n.tr(StrKey.GL_EXPORTED_ONE) else DeltaI18n.tr(StrKey.GL_EXPORTED_MANY, files)
}

// ----------------------------------------------------------------------------- Experimental

@Composable
internal fun ExperimentalFeaturesSettings(prefs: AppPreferences, nav: SettingsNav) {
    val strings = LocalDeltaStrings.current
    SettingsPage {
        FormSection(header = str(StrKey.SETTINGS_EXPERIMENTAL_FEATURES), footer = str(StrKey.EXP_FOOTER)) {
            prefs.experimentalFeatures.forEachIndexed { i, feature ->
                if (i > 0) FormDivider()
                val p = SettingsData.present(feature, strings)
                TwoLineRow(
                    title = p.name,
                    detail = p.summary,
                    trailing = { DeltaSwitch(feature.isEnabled) { v -> nav.store.setExperimental(feature.key, v) } },
                    onClick = { nav.push(SettingsRoute.ExperimentalDetail(feature.key, p.name)) },
                )
            }
        }
    }
}

@Composable
internal fun ExperimentalFeatureDetail(featureKey: String, prefs: AppPreferences, nav: SettingsNav) {
    val feature = prefs.experimentalFeatures.firstOrNull { it.key == featureKey } ?: return
    val strings = LocalDeltaStrings.current
    val p = SettingsData.present(feature, strings)
    SettingsPage {
        FormSection(footer = SettingsData.detailFooter(feature, strings)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clickable { nav.store.setExperimental(feature.key, !feature.isEnabled) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.name, style = DeltaType.Body, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                DeltaSwitch(feature.isEnabled) { v -> nav.store.setExperimental(feature.key, v) }
            }
        }
        if (feature.isEnabled && feature.options.isNotEmpty()) {
            FormSection(header = str(StrKey.COMMON_OPTIONS)) {
                feature.options.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) FormDivider()
                    val value = feature.options[key].orEmpty()
                    val next = SettingsData.nextOptionValue(key, value)
                    FormRow(
                        title = SettingsData.experimentalOptionLabel(key, strings),
                        value = SettingsData.experimentalOptionValue(value, strings),
                        onClick = next?.let { n -> { nav.store.setExperimentalOption(feature.key, key, n) } },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------- Credits

@Composable
internal fun ContributorsSettings() {
    val strings = LocalDeltaStrings.current
    SettingsPage {
        FormSection {
            SettingsData.contributors(strings).forEachIndexed { i, (name, role) ->
                if (i > 0) FormDivider()
                TwoLineRow(name, role)
            }
        }
    }
}

@Composable
internal fun SoftwareLicensesSettings() {
    SettingsPage {
        FormSection {
            SettingsData.licenses.forEachIndexed { i, (name, license) ->
                if (i > 0) FormDivider()
                LabeledRow(name, license)
            }
        }
    }
}

package com.codescv.delta.ui.library

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Outbox
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType

/** Every action of the game context menu, in Delta order (ui_spec §2.8 + design §5.1 additions). */
enum class GameMenuAction {
    PLAY, RESUME_AUTO_SAVE, TOGGLE_FAVORITE, RENAME,
    FETCH_COVER, CHANGE_COVER, RESET_COVER,
    IMPORT_SAVE, EXPORT_SAVE,
    IMPORT_STATE, EXPORT_STATE, VIEW_STATES,
    SHARE_ROM, DELETE,
}

/** One visible row of the context menu. */
data class GameMenuEntry(
    val action: GameMenuAction,
    val title: String,
    val enabled: Boolean = true,
    /** Section header shown above this row (`Cover Artwork`, …). */
    val section: String? = null,
    /** Whether a divider precedes this row. */
    val dividerBefore: Boolean = false,
)

/** Builds the context menu rows (pure; unit-tested). [info] null = still loading. */
fun gameMenuEntries(isFavorite: Boolean, info: RomMenuInfo?): List<GameMenuEntry> = buildList {
    add(GameMenuEntry(GameMenuAction.PLAY, "Play"))
    if (info?.latestAutoStateId != null) add(GameMenuEntry(GameMenuAction.RESUME_AUTO_SAVE, "Resume Auto Save"))
    add(GameMenuEntry(GameMenuAction.TOGGLE_FAVORITE, if (isFavorite) "Remove from Favorites" else "Add to Favorites"))
    add(GameMenuEntry(GameMenuAction.RENAME, "Rename…"))
    add(GameMenuEntry(GameMenuAction.FETCH_COVER, "Fetch Cover Artwork", section = "Cover Artwork"))
    add(GameMenuEntry(GameMenuAction.CHANGE_COVER, "Change Cover Artwork…"))
    add(GameMenuEntry(GameMenuAction.RESET_COVER, "Reset Cover Artwork"))
    add(GameMenuEntry(GameMenuAction.IMPORT_SAVE, "Import Save File (.sav/.srm)…", section = "Game Save (.sav / .srm)", dividerBefore = true))
    add(GameMenuEntry(GameMenuAction.EXPORT_SAVE, "Export Save File (.sav)…", enabled = info?.hasSaveRam == true))
    add(GameMenuEntry(GameMenuAction.IMPORT_STATE, "Import Save State…", section = "Save States (.svs / .state)"))
    add(GameMenuEntry(GameMenuAction.EXPORT_STATE, "Export Save State…", enabled = info?.latestState != null))
    add(GameMenuEntry(GameMenuAction.VIEW_STATES, "View Save States…"))
    add(GameMenuEntry(GameMenuAction.SHARE_ROM, "Share ROM…", dividerBefore = true))
    add(GameMenuEntry(GameMenuAction.DELETE, "Delete"))
}

private val DestructiveRed = Color(0xFFFF453A)
private val MenuBackground = Color(0xFF2C2C2E)

private fun icon(action: GameMenuAction, isFavorite: Boolean): ImageVector = when (action) {
    GameMenuAction.PLAY -> Icons.Filled.PlayArrow
    GameMenuAction.RESUME_AUTO_SAVE -> Icons.Outlined.PlayCircle
    GameMenuAction.TOGGLE_FAVORITE -> if (isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline
    GameMenuAction.RENAME -> Icons.Outlined.Edit
    GameMenuAction.FETCH_COVER -> Icons.Outlined.ArrowCircleDown
    GameMenuAction.CHANGE_COVER -> Icons.Outlined.AddPhotoAlternate
    GameMenuAction.RESET_COVER -> Icons.Outlined.HideImage
    GameMenuAction.IMPORT_SAVE -> Icons.Outlined.SaveAlt
    GameMenuAction.EXPORT_SAVE -> Icons.Outlined.IosShare
    GameMenuAction.IMPORT_STATE -> Icons.Outlined.MoveToInbox
    GameMenuAction.EXPORT_STATE -> Icons.Outlined.Outbox
    GameMenuAction.VIEW_STATES -> Icons.Outlined.GridView
    GameMenuAction.SHARE_ROM -> Icons.Outlined.Share
    GameMenuAction.DELETE -> Icons.Outlined.Delete
}

/** Long-press context menu anchored to the card it is placed next to. */
@Composable
fun GameContextMenu(
    expanded: Boolean,
    isFavorite: Boolean,
    info: RomMenuInfo?,
    onDismiss: () -> Unit,
    onAction: (GameMenuAction) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MenuBackground,
    ) {
        for (entry in gameMenuEntries(isFavorite, info)) {
            if (entry.dividerBefore) HorizontalDivider(thickness = 6.dp, color = Color(0xFF1C1C1E))
            entry.section?.let {
                Text(
                    it,
                    style = DeltaType.Footnote,
                    color = DeltaColors.SystemGray,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            val destructive = entry.action == GameMenuAction.DELETE
            val color = if (destructive) DestructiveRed else Color.White
            DropdownMenuItem(
                text = { Text(entry.title, style = DeltaType.Body) },
                trailingIcon = { Icon(icon(entry.action, isFavorite), contentDescription = null) },
                enabled = entry.enabled,
                colors = MenuDefaults.itemColors(
                    textColor = color,
                    trailingIconColor = color,
                    disabledTextColor = DeltaColors.InactiveIcon,
                    disabledTrailingIconColor = DeltaColors.InactiveIcon,
                ),
                onClick = {
                    onDismiss()
                    onAction(entry.action)
                },
            )
        }
    }
}

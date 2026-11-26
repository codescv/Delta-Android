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
import com.codescv.delta.i18n.DeltaI18n
import com.codescv.delta.i18n.DeltaStrings
import com.codescv.delta.i18n.LocalDeltaStrings
import com.codescv.delta.i18n.StrKey
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
fun gameMenuEntries(
    isFavorite: Boolean,
    info: RomMenuInfo?,
    strings: DeltaStrings = DeltaI18n.currentStrings(),
): List<GameMenuEntry> = buildList {
    add(GameMenuEntry(GameMenuAction.PLAY, strings[StrKey.MENU_PLAY]))
    if (info?.latestAutoStateId != null) add(GameMenuEntry(GameMenuAction.RESUME_AUTO_SAVE, strings[StrKey.MENU_RESUME_AUTO_SAVE]))
    add(
        GameMenuEntry(
            GameMenuAction.TOGGLE_FAVORITE,
            strings[if (isFavorite) StrKey.MENU_REMOVE_FROM_FAVORITES else StrKey.MENU_ADD_TO_FAVORITES],
        ),
    )
    add(GameMenuEntry(GameMenuAction.RENAME, strings[StrKey.MENU_RENAME]))
    add(GameMenuEntry(GameMenuAction.FETCH_COVER, strings[StrKey.MENU_FETCH_COVER], section = strings[StrKey.MENU_SECTION_COVER_ARTWORK]))
    add(GameMenuEntry(GameMenuAction.CHANGE_COVER, strings[StrKey.MENU_CHANGE_COVER]))
    add(GameMenuEntry(GameMenuAction.RESET_COVER, strings[StrKey.MENU_RESET_COVER]))
    add(
        GameMenuEntry(
            GameMenuAction.IMPORT_SAVE,
            strings[StrKey.MENU_IMPORT_SAVE],
            section = strings[StrKey.MENU_SECTION_GAME_SAVE],
            dividerBefore = true,
        ),
    )
    add(GameMenuEntry(GameMenuAction.EXPORT_SAVE, strings[StrKey.MENU_EXPORT_SAVE], enabled = info?.hasSaveRam == true))
    add(GameMenuEntry(GameMenuAction.IMPORT_STATE, strings[StrKey.MENU_IMPORT_STATE], section = strings[StrKey.MENU_SECTION_SAVE_STATES]))
    add(GameMenuEntry(GameMenuAction.EXPORT_STATE, strings[StrKey.MENU_EXPORT_STATE], enabled = info?.latestState != null))
    add(GameMenuEntry(GameMenuAction.VIEW_STATES, strings[StrKey.MENU_VIEW_STATES]))
    add(GameMenuEntry(GameMenuAction.SHARE_ROM, strings[StrKey.MENU_SHARE_ROM], dividerBefore = true))
    add(GameMenuEntry(GameMenuAction.DELETE, strings[StrKey.MENU_DELETE]))
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
    val strings = LocalDeltaStrings.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = MenuBackground,
    ) {
        for (entry in gameMenuEntries(isFavorite, info, strings)) {
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

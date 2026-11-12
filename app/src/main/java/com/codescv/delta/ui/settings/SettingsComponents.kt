package com.codescv.delta.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codescv.delta.ui.components.FormRow
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType

/** iOS destructive red (dark). */
internal val DestructiveRed = Color(0xFFFF453A)

/** Settings `SettingsCloseButton`: 36 dp glass circle with a 15 sp semibold `xmark`. */
@Composable
internal fun SettingsCloseButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .border(0.5.dp, Color(0x1AFFFFFF), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
    }
}

/** Inline navigation bar of a pushed page: `‹ <back title>` + centred 17 sp semibold title. */
@Composable
internal fun SettingsNavBar(title: String, backTitle: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
                .semantics { contentDescription = "Back" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBackIos,
                contentDescription = null,
                tint = DeltaColors.Purple,
                modifier = Modifier.size(20.dp),
            )
            Text(
                backTitle,
                color = DeltaColors.Purple,
                style = DeltaType.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
        }
        Text(
            title,
            style = DeltaType.Headline,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 120.dp),
        )
    }
}

/** Scrollable grouped-form body for a pushed page. */
@Composable
internal fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
        content = content,
    )
}

/** iOS menu-style `Picker`: title + current value; tapping opens a checkmarked menu. */
@Composable
internal fun <T> PickerRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    iconColor: Color = DeltaColors.BadgeGray,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FormRow(
            title = title,
            icon = icon,
            iconColor = iconColor,
            value = options.firstOrNull { it.first == selected }?.second,
            onClick = { open = true },
            trailing = { Text("⌃⌄", color = DeltaColors.SystemGray, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Color(0xFF2C2C2E)),
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = Color.White) },
                    leadingIcon = {
                        Box(Modifier.width(18.dp)) {
                            if (value == selected) Text("✓", color = DeltaColors.Purple, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

/** `Label(title, systemImage:)` inside a `Button`: tinted icon + tinted text row. */
@Composable
internal fun ActionRow(title: String, icon: ImageVector, color: Color = DeltaColors.Purple, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (enabled) color else color.copy(alpha = 0.4f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = DeltaType.Body, color = tint)
    }
}

/** Two-line list cell (`.body semibold` name + `.caption` secondary detail). */
@Composable
internal fun TwoLineRow(title: String, detail: String, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DeltaType.Body, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(detail, style = DeltaType.Caption, color = DeltaColors.SystemGray, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Small caption line inside a card (e.g. import result messages). */
@Composable
internal fun CaptionRow(text: String) {
    Text(
        text,
        style = DeltaType.Caption,
        color = DeltaColors.SystemGray,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

package com.codescv.delta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType

/*
 * iOS grouped-inset form grammar (design §5.5), shared by Settings, every sub-screen and the
 * pause-menu sheets: `#1C1C1E` background, r20 `#2C2C2E` section cards with 16 dp margins,
 * 52 dp rows, hairline `#545458A6` dividers inset to the text, `›` chevrons, 13 sp headers.
 */

/** Section header (sentence case) + card + optional footer. */
@Composable
fun FormSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(
                header,
                style = DeltaType.Footnote,
                color = DeltaColors.SystemGray,
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 8.dp),
            )
        } else {
            Spacer(Modifier.height(18.dp))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DeltaColors.GroupedCard),
            content = content,
        )
        if (footer != null) {
            Text(
                footer,
                style = DeltaType.Footnote,
                color = DeltaColors.SystemGray,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

/** 0.5 dp hairline inset to the text start ([inset] = 16 dp, or 57 dp when rows have badges). */
@Composable
fun FormDivider(inset: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (inset) 57.dp else 16.dp)
            .height(0.5.dp)
            .background(DeltaColors.Separator),
    )
}

/** 29 dp rounded (r 7.25) gradient badge with a white glyph — Settings row icons. */
@Composable
fun SettingsBadge(icon: ImageVector, color: Color) {
    Box(
        Modifier
            .size(29.dp)
            .clip(RoundedCornerShape(7.25.dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** Generic row: optional badge, title/subtitle, trailing value and/or chevron, optional trailing slot. */
@Composable
fun FormRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = DeltaColors.BadgeGray,
    value: String? = null,
    chevron: Boolean = false,
    titleColor: Color = Color.White,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SettingsBadge(icon, iconColor)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = DeltaType.Body,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.4f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = DeltaType.Caption, color = DeltaColors.SystemGray, maxLines = 3)
            }
        }
        if (value != null) {
            Text(
                value,
                style = DeltaType.Body,
                color = DeltaColors.SystemGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Text(
                "›",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = DeltaColors.SystemGray,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Row with a purple-tinted switch. */
@Composable
fun FormToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = DeltaColors.BadgeGray,
    enabled: Boolean = true,
) {
    FormRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = DeltaColors.Purple,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFF39393D),
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

/** iOS-style segmented control (`#767680` track, `#636366` selected). */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(DeltaColors.SegmentTrack.copy(alpha = 0.24f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected) DeltaColors.SegmentSelected else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Segmented control as a full form row (title above). */
@Composable
fun FormSegmentedRow(title: String?, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (title != null) {
            Text(title, style = DeltaType.Body, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
        }
        SegmentedControl(options, selectedIndex, onSelect, Modifier.fillMaxWidth())
    }
}

/** Purple slider row. */
@Composable
fun FormSliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = DeltaColors.Purple,
                inactiveTrackColor = Color(0xFF39393D),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        trailing?.invoke()
    }
}

/** Checkmark-selectable option row (pickers rendered as inline lists, like iOS navigation pickers). */
@Composable
fun FormCheckRow(title: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    FormRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            if (selected) Text("✓", color = DeltaColors.Purple, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        },
    )
}

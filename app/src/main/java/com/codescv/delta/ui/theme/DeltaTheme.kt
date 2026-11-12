package com.codescv.delta.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Delta 2.0.5 design tokens (ui_spec §0, design §5). */
object DeltaColors {
    val Purple = Color(0xFF8A2BE2) // Color.deltaPurple (global tint)
    val PlayPurple = Color(0xFF7A2BFA) // pause-menu Resume button
    val LibraryBackground = Color(0xFF1C1C1F)
    val GlassFill = Color(0x14FFFFFF)
    val GlassStroke = Color(0x1FFFFFFF)
    val SecondaryText = Color(0xFFA6A6A6)
    val InactiveIcon = Color(0xFF808080)
    val Capsule = Color(0xFF2B2B2B)
    val CardTitle = Color(0xFFD9D9D9)
    val BoxArtStroke = Color(0x24FFFFFF)
    val BoxArtGlassFill = Color(0xFF474747)
    val BoxArtGlassStroke = Color(0x38FFFFFF)
    val GameBackground = Color.Black
    val PauseScrim = Color(0x73000000)

    // iOS dark grouped form (Settings and all sheets).
    val GroupedBackground = Color(0xFF1C1C1E)
    val GroupedCard = Color(0xFF2C2C2E)
    val Separator = Color(0xA6545458)
    val SystemGray = Color(0xFF8E8E93)
    val SegmentTrack = Color(0xFF767680)
    val SegmentSelected = Color(0xFF636366)

    // Settings badge colors (iOS dark system colors).
    val BadgePurple = Color(0xFFBF5AF2)
    val BadgePink = Color(0xFFFF375F)
    val BadgeRed = Color(0xFFFF453A)
    val BadgeGreen = Color(0xFF30D158)
    val BadgeTeal = Color(0xFF40C8E0)
    val BadgeCyan = Color(0xFF64D2FF)
    val BadgeOrange = Color(0xFFFF9F0A)
    val BadgeBlue = Color(0xFF0A84FF)
    val BadgeIndigo = Color(0xFF5E5CE6)
    val BadgeGray = Color(0xFF8E8E93)
}

/** iOS text styles at default dynamic type. */
object DeltaType {
    val LargeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold)
    val Title2 = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
    val Title3 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val Headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(fontSize = 17.sp)
    val Subheadline = TextStyle(fontSize = 15.sp)
    val Footnote = TextStyle(fontSize = 13.sp)
    val Caption = TextStyle(fontSize = 12.sp)
    val Caption2 = TextStyle(fontSize = 11.sp)
}

private val DeltaDarkScheme = darkColorScheme(
    primary = DeltaColors.Purple,
    onPrimary = Color.White,
    secondary = DeltaColors.Purple,
    background = DeltaColors.GroupedBackground,
    surface = DeltaColors.GroupedCard,
    onSurface = Color.White,
    onBackground = Color.White,
    surfaceVariant = DeltaColors.GroupedCard,
    outline = DeltaColors.Separator,
)

/** The whole app is forced dark (DeltaAppMain `.preferredColorScheme(.dark)`). */
@Composable
fun DeltaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DeltaDarkScheme, content = content)
}

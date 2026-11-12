package com.codescv.delta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType

/**
 * iOS-style sheet (NavigationStack + inline title + Cancel/Done bar items) on the dark grouped
 * background; content uses [FormSection] cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeltaSheet(
    title: String,
    onDismiss: () -> Unit,
    leadingLabel: String? = null,
    onLeading: (() -> Unit)? = null,
    trailingLabel: String? = "Done",
    onTrailing: (() -> Unit)? = onDismiss,
    halfHeight: Boolean = false,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = !halfHeight)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = DeltaColors.GroupedBackground,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (leadingLabel != null && onLeading != null) {
                    TextButton(onClick = onLeading, modifier = Modifier.align(Alignment.CenterStart)) {
                        Text(leadingLabel, color = DeltaColors.Purple, fontSize = 17.sp)
                    }
                }
                Text(
                    title,
                    style = DeltaType.Headline,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
                )
                if (trailingLabel != null && onTrailing != null) {
                    TextButton(onClick = onTrailing, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text(trailingLabel, color = DeltaColors.Purple, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(DeltaColors.Separator))
            val body = Modifier.fillMaxWidth().let { if (scrollable) it.verticalScroll(rememberScrollState()) else it }
            Column(body) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

package com.jagones.sparkpulse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * JAG-97: single source of truth for the "Dark Sparkforge" palette.
 *
 * Every value maps 1:1 to a WebUI CSS variable, so the PC dashboard and the
 * phone mirror cannot drift apart. Screens IMPORT these tokens; no screen
 * redefines a colour. Toggling a theme means editing this file only.
 */
internal val ForgeInk = Color(0xFF06070C)        // app background   (--bg)
internal val ForgePanel = Color(0xFF121722)      // card surface     (--bg-2)
internal val ForgePanelRaised = Color(0xFF161B25) // raised surface  (--bg-3)
internal val ForgeTextMain = Color(0xFFDFE4FF)   // primary text     (--txt)
internal val ForgeTextMuted = Color(0xFF7C86AD)  // muted text       (--dim)
internal val ForgeLine = Color(0xFF1E2431)       // hairline borders (--line-2)
internal val ForgeMint = Color(0xFF4ADE80)       // ok               (--ok)
internal val ForgeBlue = Color(0xFF5AC8FA)       // info             (--info)
internal val ForgeAmber = Color(0xFFFFB020)      // warn             (--warn)
internal val ForgeCoral = Color(0xFFF87171)      // err              (--err)
internal val ForgeViolet = Color(0xFFB56CFF)     // accent 2         (--acc2)
internal val ForgeAccent = Color(0xFF8B7BF0)     // brand accent     (--accent)
internal val ForgeYou = Color(0xFF8FB6FF)        // user accent      (--you)

/** Brand gradient used by titles and the active pill. */
internal val ForgeGradient = listOf(ForgeAccent, ForgeViolet, ForgeBlue)

internal val ForgeCardShape = RoundedCornerShape(18.dp)
internal val ForgePanelShape = RoundedCornerShape(12.dp)
internal val ForgePillShape = RoundedCornerShape(50)

/** Shared card surface: Panel fill + hairline border + 18dp radius. */
@Composable
internal fun ForgeCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(ForgePanel, ForgeCardShape)
            .border(BorderStroke(1.dp, ForgeLine), ForgeCardShape)
            .padding(16.dp)
    ) { content() }
}

/** Panel surface for the lateral-rail panels (Graph/Sessions/Settings/…). */
@Composable
internal fun ForgePanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(ForgePanel, ForgePanelShape)
            .border(BorderStroke(1.dp, ForgeLine), ForgePanelShape)
            .padding(12.dp),
        content = content
    )
}

/** Text filled with the brand gradient. */
@Composable
internal fun GradientTitle(text: String, fontSize: Int = 30) {
    Text(
        text,
        style = TextStyle(
            brush = Brush.horizontalGradient(ForgeGradient),
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold
        )
    )
}

/** Status pill: coloured dot + label on a raised, bordered surface. */
@Composable
internal fun StatusPill(text: String, tint: Color) {
    Row(
        modifier = Modifier
            .background(ForgePanel, ForgePillShape)
            .border(BorderStroke(1.dp, ForgeLine), ForgePillShape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(tint, CircleShape))
        Text(
            text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 7.dp)
        )
    }
}

/** Segmented tab / primary-action pill; the active one carries the gradient. */
@Composable
internal fun AccentPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .background(
                if (selected) Brush.horizontalGradient(listOf(ForgeAccent, ForgeViolet))
                else Brush.horizontalGradient(listOf(ForgePanelRaised, ForgePanelRaised)),
                ForgePillShape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Color.White else ForgeTextMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

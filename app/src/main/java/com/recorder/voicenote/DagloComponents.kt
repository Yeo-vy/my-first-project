package com.recorder.voicenote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 웹 CSS 의 `.primary-btn` / `.btn-secondary` / `.toast` 를 옮긴 조각들.
 * Material3 기본 버튼은 모서리·색이 웹과 달라서, 같은 모양을 직접 그린다.
 */

@Composable
fun DagloPrimaryButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .background(
                if (enabled) DagloColors.Primary else DagloColors.BorderHover,
                RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DagloSecondaryButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val contentColor = when {
        !enabled -> DagloColors.TextSubtle
        danger -> DagloColors.Danger
        else -> DagloColors.TextMain
    }
    Row(
        modifier = modifier
            .background(DagloColors.BgCard, RoundedCornerShape(8.dp))
            .border(1.dp, if (danger) DagloColors.Danger.copy(alpha = 0.4f) else DagloColors.Border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text, color = contentColor, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** 웹의 showToast(): 아래 가운데에서 2.2초 떴다 사라진다. */
@Composable
fun DagloToast(message: String, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(2200)
        onDismiss()
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = message,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(bottom = 40.dp)
                .background(DagloColors.TextMain.copy(alpha = 0.94f), RoundedCornerShape(10.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

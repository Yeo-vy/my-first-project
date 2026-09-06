package com.recorder.voicenote

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * daglo 웹 화면(server/static/style.css)의 색을 그대로 옮긴 팔레트.
 *
 * 앱의 녹음 화면은 주황색 Material 테마를 쓰지만, daglo 화면은 웹과 나란히 놓고 봐도 같은
 * 서비스로 보여야 해서 CSS 변수와 같은 값을 쓴다.
 */
object DagloColors {
    val Primary = Color(0xFF2563EB)          // --primary-color
    val PrimaryHover = Color(0xFF1D4ED8)     // --primary-hover
    val PrimaryLight = Color(0xFFEFF6FF)     // --primary-light
    val TextMain = Color(0xFF1E293B)         // --text-main
    val TextMuted = Color(0xFF64748B)        // --text-muted
    val TextSubtle = Color(0xFF94A3B8)       // --text-subtle
    val BgMain = Color(0xFFF8FAFC)           // --bg-main
    val BgCard = Color(0xFFFFFFFF)           // --bg-card
    val Border = Color(0xFFE2E8F0)           // --border-color
    val BorderHover = Color(0xFFCBD5E1)      // --border-hover
    val Danger = Color(0xFFEF4444)           // --danger-color
    val Warning = Color(0xFFF59E0B)          // --warning-color
    val Star = Color(0xFFEAB308)
    val Success = Color(0xFF16A34A)
    val ChatUserBubble = Color(0xFFEFF6FF)
    val Highlight = Color(0xFFFEF08A)
}

object DagloDims {
    val SidebarWidth = 250.dp
    val PlayerHeight = 76.dp
    /** 이보다 좁으면 스크립트와 AI 패널을 좌우로 놓지 않고 탭으로 접는다 (세로 태블릿·폰) */
    val TwoPaneMinWidth = 900.dp
}

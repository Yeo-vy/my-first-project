package com.recorder.voicenote

import android.webkit.CookieManager

/**
 * 웹 화면에서 로그인하며 받은 세션 쿠키를 업로드가 함께 쓴다.
 *
 * 앱은 로그인 화면을 따로 두지 않는다. 웹에서 로그인하면 서버가 내려준 쿠키가 WebView 의
 * CookieManager 에 남고, 녹음 업로드는 그 쿠키를 헤더에 실어 보낸다. 그래서 앱에 비밀번호나
 * API 토큰을 저장할 일이 없다.
 *
 * 업로드는 앱이 꺼진 뒤에도 WorkManager 가 이어서 돌린다. CookieManager 는 메모리에만 두면
 * 프로세스가 죽을 때 사라지므로, 로그인 직후 [persist] 로 디스크에 내려 둔다.
 */
object DagloSession {

    fun cookieHeader(serverUrl: String): String? {
        if (serverUrl.isEmpty()) return null
        return try {
            CookieManager.getInstance().getCookie(serverUrl)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun hasSession(serverUrl: String): Boolean = cookieHeader(serverUrl) != null

    /** WebView 가 받은 쿠키를 디스크에 내려 둔다 (프로세스가 죽어도 업로드가 쓸 수 있게). */
    fun persist() {
        try {
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            // 쿠키를 못 내려도 이번 세션 동안은 메모리에 남아 있으므로 그대로 진행한다
        }
    }
}
